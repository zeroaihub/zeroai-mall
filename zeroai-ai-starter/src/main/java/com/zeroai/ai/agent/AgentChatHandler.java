package com.zeroai.ai.agent;

import com.zeroai.ai.config.AiAgentProperties;
import com.zeroai.ai.mall.proposal.vo.ProposalVO;
import com.zeroai.ai.web.AiEvent;
import com.zeroai.ai.web.AiEventType;
import com.zeroai.ai.web.SseSink;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 通道的请求编排：建 Agent、订阅事件流、下发 SSE、处理取消与并发。
 * <p>
 * 生命周期约定：
 * <ul>
 *   <li><b>每次请求一个 Agent</b>：工具实例持有该请求的 userId，用户隔离由对象边界保证。</li>
 *   <li><b>取消即停止计费</b>：客户端断连后释放 Reactor 订阅并调用 {@code agent.interrupt()}，
 *       不让模型继续生成。这是实施方案 Phase 4 验收项「断连后不继续消耗费用」的实现点。</li>
 *   <li><b>同会话单飞</b>：同一用户同一会话同时只允许一个在途请求，
 *       防止前端重试或双击导致重复计费与事件交错。</li>
 * </ul>
 * 本类不做鉴权：userId 由调用方在入口线程从鉴权上下文取到后传入。
 */
public class AgentChatHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentChatHandler.class);

    /** 匿名会话在 RuntimeContext 中的用户标识，仅用于 SDK 内部区分，不代表任何权限。 */
    private static final String ANONYMOUS_PRINCIPAL = "anonymous";

    private final MallAgentFactory agentFactory;
    private final AiAgentProperties properties;

    /**
     * 在途会话集合。键由 userId 与 sessionId 共同构成，避免不同用户撞同名会话。
     */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * @param agentFactory Agent 工厂，不可为 null
     * @param properties   AI 配置，用于读取会话与步数上限
     */
    public AgentChatHandler(MallAgentFactory agentFactory, AiAgentProperties properties) {
        this.agentFactory = agentFactory;
        this.properties = properties;
    }

    /**
     * 处理一次 Agent 对话，事件通过 {@code sink} 下发。
     * <p>
     * 方法本身立即返回，真正的模型调用在弹性线程池中异步执行，
     * 不占用 Web 容器请求线程。副作用：消耗模型 token；不写任何业务数据。
     *
     * @param sink       SSE 发送出口
     * @param userId     服务端鉴权得到的用户 ID，匿名为 null
     * @param sessionId  会话标识，由服务端派生，不来自请求参数
     * @param question   用户问题
     */
    public void handle(SseSink sink, Long userId, String sessionId, String question) {
        String requestKey = requestKey(userId, sessionId);
        if (!inFlight.add(requestKey)) {
            log.info("Agent 通道拒绝并发请求，userId={}, sessionId={}", userId, sessionId);
            sink.send(AiEvent.of(AiEventType.ERROR, Map.of(
                    "code", "AI_BUSY",
                    "message", "上一条消息还在处理中，请稍候再试")));
            sink.complete();
            return;
        }

        AtomicInteger steps = new AtomicInteger();
        AtomicBoolean textSeen = new AtomicBoolean(false);
        AtomicBoolean truncated = new AtomicBoolean(false);
        AtomicReference<HarnessAgent> agentRef = new AtomicReference<>();
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        // 断连优先于正常完成：先取消订阅，再中断 Agent，最后释放会话占位
        sink.onClose(() -> {
            Disposable subscription = subscriptionRef.get();
            if (subscription != null && !subscription.isDisposed()) {
                log.info("客户端断连，取消模型流，userId={}, sessionId={}", userId, sessionId);
                subscription.dispose();
            }
            interruptQuietly(agentRef.get());
        });

        MallAgentContext context = new MallAgentContext(userId, sessionId);
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(userId == null ? ANONYMOUS_PRINCIPAL : String.valueOf(userId))
                .sessionId(sessionId)
                .build();

        Flux<AgentEvent> events = Flux.defer(() -> {
                    // 建 Agent 涉及创建 workspace 目录，放到弹性线程池，避免阻塞容器线程
                    // 提议回调与模型流共用同一个 sink：confirm_action 事件随工具结果一起下发，
                    // 前端拿到提议编号后走独立确认接口，不经过 Agent
                    HarnessAgent agent = agentFactory.create(context, proposal -> onProposal(sink, proposal));
                    agentRef.set(agent);
                    return agent.streamEvents(new UserMessage(question), runtimeContext);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> {
                    closeQuietly(agentRef.get());
                    inFlight.remove(requestKey);
                    log.info("Agent 通道结束，userId={}, sessionId={}, signal={}, steps={}",
                            userId, sessionId, signal, steps.get());
                });

        subscriptionRef.set(events.subscribe(
                event -> onEvent(sink, event, steps, textSeen, truncated),
                error -> onError(sink, error, userId, sessionId),
                () -> onComplete(sink, steps, truncated)));
    }

    /**
     * 提议生成回调：下发 confirm_action 事件。
     * <p>
     * 只下发确认决策需要的字段（编号、金额、过期时间、商品行数），
     * 不下发地址、手机号与内部主键；确认本身走独立接口，由用户显式触发。
     *
     * @param sink     SSE 出口
     * @param proposal 提议视图
     */
    private void onProposal(SseSink sink, ProposalVO proposal) {
        if (proposal == null) {
            return;
        }
        sink.send(AiEvent.of(AiEventType.CONFIRM_ACTION, Map.of(
                "proposalNo", proposal.proposalNo() == null ? "" : proposal.proposalNo(),
                "actionType", proposal.actionType() == null ? "" : proposal.actionType(),
                "actualPrice", proposal.actualPrice() == null ? "" : proposal.actualPrice().toPlainString(),
                "itemCount", proposal.items() == null ? 0 : proposal.items().size(),
                "expireAt", proposal.expireAt() == null ? "" : proposal.expireAt(),
                "confirmPath", "/ai/action/confirm/" + (proposal.proposalNo() == null ? "" : proposal.proposalNo()),
                "message", proposal.message() == null ? "需要你确认后才会创建订单" : proposal.message())));
    }

    /**
     * 单个事件处理：白名单映射 + 特殊事件兜底。
     *
     * @param sink      SSE 出口
     * @param event     AgentScope 事件
     * @param steps     工具调用步数计数器
     * @param textSeen  是否已下发过文本增量
     * @param truncated 是否因超出步数上限被截断
     */
    private void onEvent(SseSink sink,
                         AgentEvent event,
                         AtomicInteger steps,
                         AtomicBoolean textSeen,
                         AtomicBoolean truncated) {
        if (AgentEventMapper.isToolCall(event)) {
            steps.incrementAndGet();
        }
        if (event instanceof ExceedMaxItersEvent) {
            truncated.set(true);
            log.warn("Agent 达到最大步数上限 {}", properties.getMaxSteps());
            return;
        }
        // 本模块只注册只读工具，不应出现需要用户确认或外部执行的请求；
        // 一旦出现说明工具链被改坏，按错误上报而不是硬编码确认流程。
        if (event instanceof RequireUserConfirmEvent || event instanceof RequireExternalExecutionEvent) {
            log.error("Agent 出现未预期的写操作确认请求，工具注册可能被破坏");
            sink.send(AiEvent.of(AiEventType.ERROR, Map.of(
                    "code", "AI_UNEXPECTED_WRITE",
                    "message", "当前不支持该操作，请通过商品页正常下单")));
            return;
        }
        // 兜底：模型未产生流式增量时，用最终结果补一段文本，避免前端空白
        if (event instanceof AgentResultEvent resultEvent && !textSeen.get()) {
            String text = resultEvent.getResult() == null ? null : resultEvent.getResult().getTextContent();
            if (text != null && !text.isBlank()) {
                textSeen.set(true);
                sink.send(AiEvent.of(AiEventType.MESSAGE_DELTA, Map.of("delta", text)));
            }
            return;
        }
        AgentEventMapper.map(event).ifPresent(mapped -> {
            if (AiEventType.MESSAGE_DELTA.equals(mapped.type())) {
                textSeen.set(true);
            }
            sink.send(mapped);
        });
    }

    /**
     * 流异常处理：对外只给可读文案，异常细节进日志且不含凭据。
     *
     * @param sink      SSE 出口
     * @param error     异常
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     */
    private void onError(SseSink sink, Throwable error, Long userId, String sessionId) {
        String code = classify(error);
        log.error("Agent 对话失败，userId={}, sessionId={}, code={}", userId, sessionId, code, error);
        sink.send(AiEvent.of(AiEventType.ERROR, Map.of(
                "code", code,
                "message", friendlyMessage(code))));
        sink.complete();
    }

    /**
     * 流正常结束：下发 done 事件，携带步数与截断标记。
     *
     * @param sink      SSE 出口
     * @param steps     工具调用步数
     * @param truncated 是否被步数上限截断
     */
    private void onComplete(SseSink sink, AtomicInteger steps, AtomicBoolean truncated) {
        sink.send(AiEvent.of(AiEventType.DONE, Map.of(
                "steps", steps.get(),
                "truncated", truncated.get())));
        sink.complete();
    }

    /**
     * 组装在途请求键。用户维度参与，保证不同用户不会互相阻塞。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @return 唯一请求键
     */
    private static String requestKey(Long userId, String sessionId) {
        return (userId == null ? ANONYMOUS_PRINCIPAL : userId) + "|" + sessionId;
    }

    /**
     * 把异常归类为稳定错误码。只做字符串特征判断，不向上抛。
     *
     * @param error 异常
     * @return 错误码
     */
    private static String classify(Throwable error) {
        if (error == null) {
            return "AI_STREAM_ERROR";
        }
        String text = String.valueOf(error.getMessage()).toLowerCase();
        if (text.contains("401") || text.contains("unauthorized")
                || text.contains("authentication") || text.contains("invalid api key")) {
            return "AI_AUTH_FAILED";
        }
        if (text.contains("timeout") || text.contains("timed out")) {
            return "AI_TIMEOUT";
        }
        return "AI_STREAM_ERROR";
    }

    /**
     * 错误码对应的对外文案，不暴露任何内部细节。
     *
     * @param code 错误码
     * @return 中文提示
     */
    private static String friendlyMessage(String code) {
        return switch (code) {
            case "AI_AUTH_FAILED" -> "AI 服务暂时不可用，请稍后再试";
            case "AI_TIMEOUT" -> "回答超时，请稍后再试或换个问法";
            default -> "服务繁忙，请稍后再试";
        };
    }

    /**
     * 中断 Agent，忽略重复中断与已关闭带来的异常。
     *
     * @param agent Agent 实例，可为 null
     */
    private static void interruptQuietly(HarnessAgent agent) {
        if (agent == null) {
            return;
        }
        try {
            agent.interrupt();
        } catch (RuntimeException e) {
            log.debug("中断 Agent 失败（可能已结束）：{}", e.getMessage());
        }
    }

    /**
     * 释放 Agent 资源，幂等。
     *
     * @param agent Agent 实例，可为 null
     */
    private static void closeQuietly(HarnessAgent agent) {
        if (agent == null) {
            return;
        }
        try {
            agent.close();
        } catch (RuntimeException e) {
            log.debug("关闭 Agent 失败：{}", e.getMessage());
        }
    }
}
