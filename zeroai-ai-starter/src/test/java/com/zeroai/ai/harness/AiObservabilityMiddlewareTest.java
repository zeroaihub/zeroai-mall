package com.zeroai.ai.harness;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可观测中间件测试。
 * <p>
 * 两个目标：
 * <ol>
 *   <li><b>不改变行为</b>：事件流必须逐一透传，顺序与内容不变；</li>
 *   <li><b>确实留痕</b>：模型调用与工具调用各产生一条指标日志，且不含 Prompt 正文。</li>
 * </ol>
 * 第二条用 logback 的 {@link ListAppender} 抓取，避免把日志格式断言写死在字符串匹配里
 * 之后又因为改文案而误报。
 */
class AiObservabilityMiddlewareTest {

    private final AiObservabilityMiddleware middleware = new AiObservabilityMiddleware();
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(AiObservabilityMiddleware.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
    }

    private static RuntimeContext ctx() {
        return RuntimeContext.builder().sessionId("u93").userId("93").build();
    }

    private List<String> formattedLogs() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("模型调用事件逐一透传，且记录耗时与 token")
    void passesThroughModelEventsAndRecordsUsage() {
        TextBlockDeltaEvent delta = new TextBlockDeltaEvent("r1", "b-1", "hello");
        ModelCallEndEvent end = new ModelCallEndEvent("r1", new ChatUsage(406, 58, 464));
        ModelCallInput input = new ModelCallInput(List.of(), List.of(), null, null);

        List<AgentEvent> received = middleware.onModelCall(null, ctx(), input, in -> Flux.just(delta, end))
                .collectList()
                .block();

        assertEquals(List.of(delta, end), received, "事件流必须原样透传");

        List<String> logs = formattedLogs();
        assertEquals(1, logs.size(), "每次模型调用应恰好一条指标日志，实际=" + logs);
        String line = logs.get(0);
        assertTrue(line.contains("ai.model.call"), line);
        assertTrue(line.contains("session=u93"), line);
        assertTrue(line.contains("in=406"), line);
        assertTrue(line.contains("out=58"), line);
        assertTrue(line.contains("total=464"), line);
        assertTrue(line.contains("signal=onComplete"), "SignalType.toString() 为 onComplete：" + line);
    }

    @Test
    @DisplayName("拿不到 usage 时标注 missing，而不是记成 0")
    void marksUsageMissingInsteadOfZero() {
        ModelCallInput input = new ModelCallInput(List.of(), List.of(), null, null);

        middleware.onModelCall(null, ctx(), input, in -> Flux.empty()).collectList().block();

        String line = formattedLogs().get(0);
        assertTrue(line.contains("usage=missing"), line);
        assertTrue(!line.contains("in=0"), "缺 usage 不能伪装成 0 token：" + line);
    }

    @Test
    @DisplayName("工具调用记录工具名、耗时与结果状态")
    void recordsToolNameAndState() {
        ToolUseBlock call = new ToolUseBlock("call-1", "searchProducts", Map.of("keyword", "手机"));
        ActingInput input = new ActingInput(List.of(call));
        ToolResultEndEvent end = new ToolResultEndEvent("r1", "call-1", "searchProducts", ToolResultState.SUCCESS);

        List<AgentEvent> received = middleware.onActing(null, ctx(), input, in -> Flux.just(end))
                .collectList()
                .block();

        assertEquals(List.of(end), received, "工具事件必须原样透传");

        List<String> logs = formattedLogs();
        assertEquals(1, logs.size(), "每次工具结果应恰好一条日志，实际=" + logs);
        String line = logs.get(0);
        assertTrue(line.contains("ai.tool.call"), line);
        assertTrue(line.contains("tool=searchProducts"), line);
        assertTrue(line.contains("state=SUCCESS"), line);
        assertTrue(line.contains("ms="), line);
    }

    @Test
    @DisplayName("工具失败也要留痕，状态为 ERROR")
    void recordsToolFailure() {
        ToolUseBlock call = new ToolUseBlock("call-2", "get_product_detail", Map.of("goodsId", 1));
        ActingInput input = new ActingInput(List.of(call));
        ToolResultEndEvent end = new ToolResultEndEvent("r1", "call-2", "get_product_detail", ToolResultState.ERROR);

        middleware.onActing(null, ctx(), input, in -> Flux.just(end)).collectList().block();

        assertTrue(formattedLogs().get(0).contains("state=ERROR"), formattedLogs().get(0));
    }

    @Test
    @DisplayName("日志绝不含 Prompt 正文与工具入参")
    void neverLogsPromptOrToolInput() {
        ToolUseBlock call = new ToolUseBlock("call-3", "searchProducts", Map.of("keyword", "私密关键词"));
        ActingInput acting = new ActingInput(List.of(call));
        ToolResultEndEvent end = new ToolResultEndEvent("r1", "call-3", "searchProducts", ToolResultState.SUCCESS);
        ModelCallInput modelInput = new ModelCallInput(List.of(), List.of(), null, null);

        middleware.onActing(null, ctx(), acting, in -> Flux.just(end)).collectList().block();
        middleware.onModelCall(null, ctx(), modelInput, in -> Flux.empty()).collectList().block();

        String all = String.join("\n", formattedLogs());
        assertTrue(!all.contains("私密关键词"), "工具入参泄漏到日志：" + all);
    }

    @Test
    @DisplayName("下游抛异常时记录失败并原样抛出")
    void propagatesExceptionAndLogsFailure() {
        ModelCallInput input = new ModelCallInput(List.of(), List.of(), null, null);

        assertThrows(IllegalStateException.class, () -> middleware.onModelCall(null, ctx(), input,
                        in -> {
                            throw new IllegalStateException("boom");
                        })
                .collectList()
                .block());

        String line = formattedLogs().get(0);
        assertTrue(line.contains("ai.model.call.failed"), line);
        assertTrue(line.contains("error=IllegalStateException"), line);
    }

    @Test
    @DisplayName("上下文缺失时降级为 anonymous，不抛空指针")
    void toleratesMissingContext() {
        ModelCallInput input = new ModelCallInput(List.of(), List.of(), null, null);

        middleware.onModelCall(null, null, input, in -> Flux.empty()).collectList().block();

        assertTrue(formattedLogs().get(0).contains("session=anonymous"), formattedLogs().get(0));
    }

    @Test
    @DisplayName("工具名含非法字符时被清洗，避免日志注入")
    void sanitizesToolName() {
        ToolUseBlock call = new ToolUseBlock("call-4", "evil\nname=injected", Map.of());
        ActingInput input = new ActingInput(List.of(call));
        ToolResultEndEvent end = new ToolResultEndEvent("r1", "call-4", "evil\nname=injected", ToolResultState.SUCCESS);

        middleware.onActing(null, ctx(), input, in -> Flux.just(end)).collectList().block();

        String line = formattedLogs().get(0);
        assertTrue(!line.contains("\n"), "日志被换行注入：" + line);
        assertTrue(line.contains("tool=evil_name_injected"), line);
    }
}
