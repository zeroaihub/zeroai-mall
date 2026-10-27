package com.zeroai.ai.agent;

import com.zeroai.ai.config.AiAgentProperties;
import com.zeroai.ai.web.AiEvent;
import com.zeroai.ai.web.AiEventType;
import com.zeroai.ai.web.SseSink;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 通道编排的行为测试。
 * <p>
 * 只替换模型与 Agent 边界（Mockito），SSE 出口使用真实的 {@link SseSink}，
 * 因此验证的是「事件真的按协议下发了」，而不是「某个方法被调用过」。
 * 全部用例不访问网络。
 */
class AgentChatHandlerTest {

    private final MallAgentFactory agentFactory = mock(MallAgentFactory.class);

    private final HarnessAgent agent = mock(HarnessAgent.class);

    private final AiAgentProperties properties = new AiAgentProperties();

    private final AgentChatHandler handler = new AgentChatHandler(agentFactory, properties, null);

    private final CapturingEmitter emitter = new CapturingEmitter();

    private final SseSink sink = new SseSink(emitter);

    /**
     * 把指定事件流挂到 mock Agent 上。
     *
     * @param events 该次请求要产出的事件流
     */
    private void givenAgentEmits(Flux<AgentEvent> events) {
        when(agentFactory.create(any(), any())).thenReturn(agent);
        when(agent.streamEvents(org.mockito.ArgumentMatchers.<Msg>any(), any(RuntimeContext.class)))
                .thenReturn(events);
    }

    @Test
    void shouldEmitDeltaAndToolEventsInProtocolOrder() throws InterruptedException {
        givenAgentEmits(Flux.just(
                new TextBlockDeltaEvent("r", "b1", "这款耳机"),
                new ToolCallStartEvent("r", "c1", "search_products"),
                new ToolResultEndEvent("r", "c1", "search_products", ToolResultState.SUCCESS),
                new TextBlockDeltaEvent("r", "b2", "有现货")));

        handler.handle(sink, 1001L, "u1001", "有没有耳机");
        await(sink::isClosed);

        List<String> types = emitter.payloads().stream().map(AiEvent.class::cast).map(AiEvent::type).toList();
        assertEquals(List.of(
                AiEventType.MESSAGE_DELTA,
                AiEventType.THINKING,
                AiEventType.TOOL_RESULT,
                AiEventType.MESSAGE_DELTA,
                AiEventType.DONE), types);

        AiEvent done = (AiEvent) emitter.payloads().get(4);
        assertEquals(1, ((java.util.Map<?, ?>) done.data()).get("steps"));
        assertEquals(Boolean.FALSE, ((java.util.Map<?, ?>) done.data()).get("truncated"));
    }

    @Test
    void shouldNotLeakReasoningChainToClient() throws InterruptedException {
        givenAgentEmits(Flux.just(
                new ThinkingBlockDeltaEvent("r", "b1", "用户想比价，先查库存再报价"),
                new TextBlockDeltaEvent("r", "b2", "稍等")));

        handler.handle(sink, 1001L, "u1001", "哪个便宜");
        await(sink::isClosed);

        String serialized = emitter.payloads().toString();
        assertFalse(serialized.contains("比价"), "推理链正文不得出现在任何下行事件中");
    }

    @Test
    void shouldMapAuthFailureToStableCodeWithoutLeakingCause() throws InterruptedException {
        givenAgentEmits(Flux.error(new IllegalStateException("401 Unauthorized: sk-super-secret")));

        handler.handle(sink, 1001L, "u1001", "你好");
        await(sink::isClosed);

        AiEvent error = (AiEvent) emitter.payloads().getFirst();
        assertEquals(AiEventType.ERROR, error.type());
        assertEquals("AI_AUTH_FAILED", ((java.util.Map<?, ?>) error.data()).get("code"));
        assertFalse(error.data().toString().contains("sk-super-secret"), "异常原文不得下发");
    }

    @Test
    void shouldMapGenericFailureToStreamError() throws InterruptedException {
        givenAgentEmits(Flux.error(new IllegalStateException("connect reset by peer")));

        handler.handle(sink, 1001L, "u1001", "你好");
        await(sink::isClosed);

        AiEvent error = (AiEvent) emitter.payloads().getFirst();
        assertEquals("AI_STREAM_ERROR", ((java.util.Map<?, ?>) error.data()).get("code"));
    }

    @Test
    void shouldRejectConcurrentRequestOnSameSession() {
        // 首个请求永不结束，模拟「上一条还在生成」
        givenAgentEmits(Flux.never());

        handler.handle(sink, 1001L, "u1001", "第一个问题");
        verify(agentFactory, timeout(3000)).create(any(), any());

        CapturingEmitter secondEmitter = new CapturingEmitter();
        SseSink secondSink = new SseSink(secondEmitter);
        handler.handle(secondSink, 1001L, "u1001", "重复点击");

        AiEvent rejected = (AiEvent) secondEmitter.payloads().getFirst();
        assertEquals(AiEventType.ERROR, rejected.type());
        assertEquals("AI_BUSY", ((java.util.Map<?, ?>) rejected.data()).get("code"));
        assertTrue(secondSink.isClosed());
    }

    @Test
    void shouldAllowDifferentUsersToRunInParallel() {
        givenAgentEmits(Flux.never());

        handler.handle(sink, 1001L, "u1001", "问题一");
        verify(agentFactory, timeout(3000)).create(any(), any());

        CapturingEmitter otherEmitter = new CapturingEmitter();
        SseSink otherSink = new SseSink(otherEmitter);
        handler.handle(otherSink, 1002L, "u1002", "问题二");

        assertTrue(otherEmitter.payloads().isEmpty(), "不同用户不应被并发保护误伤");
        assertFalse(otherSink.isClosed());
    }

    @Test
    void shouldInterruptAgentWhenConnectionCloses() {
        givenAgentEmits(Flux.never());

        handler.handle(sink, 1001L, "u1001", "长问题");
        verify(agentFactory, timeout(3000)).create(any(), any());

        // 模拟客户端断开 / 容器结束连接
        sink.complete();

        verify(agent, timeout(3000)).interrupt();
    }

    /**
     * 轮询等待异步流结束，避免依赖固定 sleep。
     *
     * @param condition 结束条件
     * @throws InterruptedException 线程被中断
     */
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "等待事件流结束超时");
    }

    /**
     * 捕获型 SSE 连接：只记录下发的事件，不依赖 Servlet 容器。
     * <p>
     * 覆盖 {@code send(SseEventBuilder)} 而不是底层 handler，
     * 这样断言的正是生产代码实际组装的事件对象。
     */
    private static final class CapturingEmitter extends SseEmitter {

        private final List<Object> payloads = new CopyOnWriteArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            // SseEventBuilder 会把事件名与数据拆成多个片段，这里只保留业务事件对象
            builder.build().stream()
                    .map(item -> item.getData())
                    .filter(AiEvent.class::isInstance)
                    .forEach(payloads::add);
        }

        /**
         * @return 已下发的事件载荷，按发送顺序
         */
        private List<Object> payloads() {
            return payloads;
        }
    }
}
