package com.zeroai.ai.harness;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * AI 调用可观测中间件。
 * <p>
 * 只做观测，<b>不修改</b>任何输入输出：中间件拿到 next 后原样返回，
 * 仅在事件流旁路记录耗时、token 与工具结果状态。这样即使中间件有 bug，
 * 也不会改变模型行为（最坏情况是日志缺失或异常被上游感知）。
 * <p>
 * <b>硬约束：不记录 Prompt 正文。</b>
 * {@link ModelCallInput#messages()} 与 {@link ToolUseBlock#getInput()} 都含用户原始输入
 * （可能有手机号、地址、订单号），因此一律不落日志。只记录结构性指标：
 * 消息条数、工具个数、工具名、耗时、token 数、结果状态。
 * <p>
 * 日志采用 {@code key=value} 形式，便于 grep 与后续接指标系统：
 * <pre>
 * ai.model.call session=u93 model=? ms=1832 in=406 out=58 total=464 signal=ON_COMPLETE
 * ai.tool.call session=u93 tool=searchProducts ms=12 state=SUCCESS
 * </pre>
 */
public class AiObservabilityMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AiObservabilityMiddleware.class);

    /**
     * 中间件顺序。数值越小越靠外层：观测层应在所有业务中间件之外，
     * 才能把别人的耗时也算进来。
     */
    private static final int ORDER = -100;

    @Override
    public int order() {
        return ORDER;
    }

    /**
     * 拦截一次模型调用，记录耗时与 token 用量。
     * <p>
     * 代理对象、失败重试等场景下本方法会被多次调用，每次独立计时。
     *
     * @param agent Agent 实例，仅用于日志定位
     * @param ctx   运行时上下文，提供会话与用户标识
     * @param input 模型调用输入，<b>只读取条数，不读取内容</b>
     * @param next  责任链下游
     * @return 原样透传的事件流
     */
    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        long startNanos = System.nanoTime();
        int messageCount = input == null || input.messages() == null ? 0 : input.messages().size();
        int toolCount = input == null || input.tools() == null ? 0 : input.tools().size();
        AtomicReference<ChatUsage> usageRef = new AtomicReference<>();

        Flux<AgentEvent> downstream;
        try {
            downstream = next.apply(input);
        } catch (RuntimeException e) {
            // 同步失败也要留一条记录，否则「模型调用失败」在日志里完全消失
            log.warn("ai.model.call.failed session={} ms={} msgCount={} toolCount={} error={}",
                    session(ctx), elapsedMs(startNanos), messageCount, toolCount, e.getClass().getSimpleName());
            throw e;
        }

        return downstream
                .doOnNext(event -> {
                    if (event instanceof ModelCallEndEvent end && end.getUsage() != null) {
                        usageRef.set(end.getUsage());
                    }
                })
                .doFinally(signal -> logModelCall(ctx, startNanos, messageCount, toolCount, usageRef.get(), signal));
    }

    /**
     * 拦截一次工具执行，记录工具名、耗时与结果状态。
     * <p>
     * 一次 {@link ActingInput} 可能包含多个并行工具调用，按 toolCallId 分别计时。
     * 只记录工具名与状态，不记录入参与返回值。
     *
     * @param agent Agent 实例
     * @param ctx   运行时上下文
     * @param input 本轮的工具调用列表，<b>只读取 id 与 name</b>
     * @param next  责任链下游
     * @return 原样透传的事件流
     */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        Map<String, Long> startTimes = new ConcurrentHashMap<>();
        Map<String, String> toolNames = new ConcurrentHashMap<>();
        List<ToolUseBlock> calls = input == null ? null : input.toolCalls();
        if (calls != null) {
            for (ToolUseBlock call : calls) {
                if (call == null || call.getId() == null) {
                    continue;
                }
                startTimes.put(call.getId(), System.nanoTime());
                toolNames.put(call.getId(), call.getName());
            }
        }

        Flux<AgentEvent> downstream;
        try {
            downstream = next.apply(input);
        } catch (RuntimeException e) {
            log.warn("ai.tool.batch.failed session={} tools={} error={}",
                    session(ctx), toolNames.values(), e.getClass().getSimpleName());
            throw e;
        }

        return downstream.doOnNext(event -> {
            if (!(event instanceof ToolResultEndEvent end)) {
                return;
            }
            String callId = end.getToolCallId();
            Long started = callId == null ? null : startTimes.remove(callId);
            String name = end.getToolCallName() != null
                    ? end.getToolCallName()
                    : (callId == null ? null : toolNames.get(callId));
            long ms = started == null ? -1L : elapsedMs(started);
            log.info("ai.tool.call session={} tool={} ms={} state={}",
                    session(ctx), sanitizeToolName(name), ms,
                    end.getState() == null ? "UNKNOWN" : end.getState().name());
        });
    }

    private void logModelCall(RuntimeContext ctx, long startNanos, int messageCount, int toolCount,
                              ChatUsage usage, SignalType signal) {
        long ms = elapsedMs(startNanos);
        if (usage == null) {
            // 取消或提前终止时拿不到 usage，明确标注而不是填 0
            log.info("ai.model.call session={} ms={} msgCount={} toolCount={} usage=missing signal={}",
                    session(ctx), ms, messageCount, toolCount, signal);
            return;
        }
        log.info("ai.model.call session={} ms={} msgCount={} toolCount={} in={} out={} cached={} total={} signal={}",
                session(ctx), ms, messageCount, toolCount,
                usage.getInputTokens(), usage.getOutputTokens(), usage.getCachedTokens(),
                usage.getTotalTokens(), signal);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String session(RuntimeContext ctx) {
        if (ctx == null || ctx.getSessionId() == null) {
            return "anonymous";
        }
        return ctx.getSessionId();
    }

    /**
     * 工具名来自模型输出，属于不可信内容；只保留安全字符，避免日志注入。
     */
    private static String sanitizeToolName(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        return name.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
