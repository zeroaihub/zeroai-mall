package com.zeroai.ai.web;

import com.zeroai.ai.agent.AgentChatHandler;
import com.zeroai.ai.config.AiAgentProperties;
import com.zeroai.ai.engine.ChatEngineNames;
import com.zeroai.ai.engine.ChatEngineSelector;
import com.zeroai.ai.engine.ChatStreamEngine;
import com.zeroai.ai.engine.ChatStreamHandle;
import com.zeroai.ai.engine.ChatStreamListener;
import com.zeroai.ai.engine.langchain4j.agent.Lc4jAgentHandler;
import com.zeroai.ai.history.AiChatHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用流式对话入口。
 * <p>
 * 由 starter 提供，业务项目无需重复实现；端点路径可通过 {@code zeroai.ai.chat-path} 调整。
 * 本类只负责「选择引擎 + 把输出转成 SSE 事件」，不含任何业务逻辑，也不做鉴权——
 * 鉴权由接入方在自身拦截器中完成，用户身份通过 {@link AiUserContext} 注入。
 * <p>
 * 引擎取值（{@code zeroai.ai.engine}）与对外契约：
 * <ul>
 *   <li>{@code spring-ai}（默认）：单轮无工具问答，走 Spring AI 适配器。</li>
 *   <li>{@code langchain4j}：单轮无工具问答，走 LangChain4j 适配器；与 spring-ai 同契约、同配置项。</li>
 *   <li>{@code agentscope}：带只读商城工具的 Agent，走 AgentScope Harness。</li>
 * </ul>
 * 三种取值的 SSE 事件协议完全一致，前端不感知差异。
 * <p>
 * <b>本类不依赖任何模型 SDK 类型</b>：只面向 {@link ChatStreamEngine} 端口，
 * 新增引擎不需要改这里，这也是 Spring AI 与 LangChain4j 能被彻底隔开的边界。
 * <p>
 * 注意：本端点必须在入口线程取到用户快照，异步回调中不再访问 ThreadLocal。
 */
@RestController
@ConditionalOnProperty(prefix = "zeroai.ai", name = "enabled", havingValue = "true")
public class AiChatController {

    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);

    /**
     * 单轮引擎实现集合。
     * <p>
     * 用 {@link ObjectProvider} 而不是直接注入具体实现：装配结果取决于 {@code zeroai.ai.engine}，
     * 直接按类型注入会在多实现时抛 {@code NoUniqueBeanDefinitionException}，
     * 而按名字选引擎正是本类的职责（见 {@link ChatEngineSelector}）。
     */
    private final ObjectProvider<ChatStreamEngine> engineProvider;
    private final AiAgentProperties properties;
    private final AiUserContext userContext;

    /**
     * Agent 通道处理器。
     * <p>
     * 用 {@link ObjectProvider} 而非直接注入：未启用 agentscope 时 Agent 装配不生效，
     * 直接注入会因缺少 Bean 导致启动失败。取不到即代表当前未启用 Agent 通道。
     */
    private final ObjectProvider<AgentChatHandler> agentHandlerProvider;

    /**
     * LangChain4j Agent 编排器。
     * <p>
     * 与 {@link #agentHandlerProvider} 同理：只在 {@code engine=langchain4j} 时装配，
     * 取不到即代表当前未启用该通道（回落单轮）。
     */
    private final ObjectProvider<Lc4jAgentHandler> lc4jHandlerProvider;

    /**
     * 对话历史门面。用 {@link ObjectProvider} 取，取不到时按「不记录历史」处理。
     */
    private final ObjectProvider<AiChatHistory> chatHistoryProvider;

    public AiChatController(ObjectProvider<ChatStreamEngine> engineProvider,
                            AiAgentProperties properties,
                            AiUserContext userContext,
                            ObjectProvider<AgentChatHandler> agentHandlerProvider,
                            ObjectProvider<Lc4jAgentHandler> lc4jHandlerProvider,
                            ObjectProvider<AiChatHistory> chatHistoryProvider) {
        this.engineProvider = engineProvider;
        this.properties = properties;
        this.userContext = userContext;
        this.agentHandlerProvider = agentHandlerProvider;
        this.lc4jHandlerProvider = lc4jHandlerProvider;
        this.chatHistoryProvider = chatHistoryProvider;
    }

    /**
     * 流式对话。
     * <p>
     * 前置条件：调用方已完成登录校验。副作用：无写操作，仅消耗模型 token。
     *
     * @param question 用户问题
     * @return SSE 事件流，事件类型见 {@link AiEventType}
     */
    @GetMapping(value = "${zeroai.ai.chat-path:/ai/chat}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestParam("q") String question) {
        // 显式设置超时：容器默认 30s 会把长回答截断
        SseSink sink = new SseSink(new SseEmitter(properties.getSseTimeoutMs()));

        if (!StringUtils.hasText(question)) {
            sink.send(AiEvent.of(AiEventType.ERROR,
                    Map.of("code", "EMPTY_QUESTION", "message", "请输入你想咨询的内容")));
            sink.complete();
            return sink.emitter();
        }

        // 入口线程取用户快照：异步回调里 ThreadLocal 已失效，不能再取
        Long userId = userContext.currentUserId();
        AgentChatHandler agentHandler = resolveAgentHandler();
        String sessionId = deriveSessionId(userId);

        /*
         * 先下发一条过程态再开跑。
         * 实测首字延迟 1~4 秒（工具链路更长），此前这段时间页面上只有用户自己的气泡，
         * 用户会以为请求卡死。这条事件让「已收到、正在处理」立刻可见，
         * 后续真正的工具进度会以同名事件继续覆盖展示。
         */
        sink.send(AiEvent.of(AiEventType.THINKING, Map.of(
                "status", "running",
                "tool", "other",
                "message", "正在理解你的问题")));

        if (agentHandler != null) {
            log.info("AI 对话分发 userId={} sessionId={} engine={} channel=agent questionLength={} preview=\"{}\"",
                    userId, sessionId, properties.getEngine(), question.length(), preview(question));
            agentHandler.handle(sink, userId, sessionId, question);
            return sink.emitter();
        }

        // LangChain4j Agent 通道（带工具）：装配未生效时回落单轮，与 agentscope 的降级策略一致
        if (ChatEngineNames.LANGCHAIN4J.equalsIgnoreCase(properties.getEngine())) {
            Lc4jAgentHandler lc4jHandler = lc4jHandlerProvider.getIfAvailable();
            if (lc4jHandler != null) {
                log.info("AI 对话分发 userId={} sessionId={} engine={} channel=lc4j-agent questionLength={} preview=\"{}\"",
                        userId, sessionId, properties.getEngine(), question.length(), preview(question));
                lc4jHandler.handle(sink, userId, sessionId, question);
                return sink.emitter();
            }
            log.warn("zeroai.ai.engine=langchain4j 但 Agent 装配未生效，降级为单轮通道");
        }

        ChatStreamEngine engine = resolveStreamEngine();
        if (engine == null) {
            // 理论上不会出现：spring-ai 适配器始终注册。真出现说明装配被外部破坏，
            // 明确报错比让用户对着空气泡等超时更可诊断。
            log.error("单轮对话引擎缺失，userId={}, engine={}", userId, properties.getEngine());
            sink.send(AiEvent.of(AiEventType.ERROR,
                    Map.of("code", "AI_ENGINE_UNAVAILABLE", "message", "AI 引擎未就绪，请稍后再试")));
            sink.complete();
            return sink.emitter();
        }

        log.info("AI 对话分发 userId={} sessionId={} engine={} resolved={} channel=single-turn questionLength={} preview=\"{}\"",
                userId, sessionId, properties.getEngine(), engine.name(), question.length(), preview(question));
        streamSingleTurn(sink, userId, sessionId, question, engine);
        return sink.emitter();
    }

    /**
     * 问题预览：压成单行并截断，日志里只留判断问题所需的片段。
     *
     * @param question 用户问题
     * @return 不超过 40 字的预览
     */
    private static String preview(String question) {
        String flat = question.replaceAll("\\s+", " ").trim();
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "…";
    }

    /**
     * 单轮问答通道：无工具、无会话。
     * <p>
     * 仍然记录历史：即使引擎切回单轮通道，用户重进页面也该看到聊过什么。
     * 引擎差异全部由 {@link ChatStreamEngine} 承担，这里只处理「事件 → SSE + 历史」。
     *
     * @param sink      SSE 出口
     * @param userId    用户 ID，仅用于日志与历史归属
     * @param sessionId 会话标识，仅用于历史归档
     * @param question  用户问题
     * @param engine    已选中的引擎
     */
    private void streamSingleTurn(SseSink sink, Long userId, String sessionId, String question,
                                  ChatStreamEngine engine) {
        AiChatHistory history = chatHistoryProvider.getIfAvailable();
        AiChatHistory.Turn turn = history == null ? null : history.begin(userId, sessionId, question);

        /*
         * 客户端断连时同样要收尾：既不继续消耗 token，也要把已产出的内容存档。
         * 具体能停到什么程度由引擎决定（见 ChatStreamHandle 的说明）。
         */
        AtomicReference<ChatStreamHandle> handleRef = new AtomicReference<>();
        sink.onClose(() -> {
            if (turn != null) {
                turn.fail("CLIENT_ABORT", 0);
            }
            ChatStreamHandle handle = handleRef.get();
            if (handle != null) {
                handle.cancel();
            }
        });

        ChatStreamListener listener = new ChatStreamListener() {
            @Override
            public void onDelta(String delta) {
                if (turn != null) {
                    turn.delta(delta);
                }
                sink.send(AiEvent.of(AiEventType.MESSAGE_DELTA, Map.of("delta", delta)));
            }

            @Override
            public void onError(Throwable error) {
                log.error("AI 流式对话失败，engine={} userId={}", engine.name(), userId, error);
                if (turn != null) {
                    turn.fail("AI_STREAM_ERROR", 0);
                }
                sink.send(AiEvent.of(AiEventType.ERROR,
                        Map.of("code", "AI_STREAM_ERROR", "message", "服务繁忙，请稍后再试")));
                sink.complete();
            }

            @Override
            public void onComplete() {
                if (turn != null) {
                    turn.success(0);
                }
                log.info("单轮通道回答完成 engine={} userId={} sessionId={}", engine.name(), userId, sessionId);
                sink.send(AiEvent.of(AiEventType.DONE, Map.of("steps", 0)));
                sink.complete();
            }
        };

        handleRef.set(engine.stream(question, listener));
    }

    /**
     * 选出单轮引擎。
     * <p>
     * 配置写 {@code langchain4j} 但装配未生效（依赖缺失、模型构造失败）时，
     * 回落到 spring-ai 并留下告警，而不是让整个对话入口不可用——
     * 与 agentscope 装配失败时的降级策略保持一致。
     *
     * @return 选中的引擎；一个都没有时返回 null
     */
    private ChatStreamEngine resolveStreamEngine() {
        List<ChatStreamEngine> engines = engineProvider.orderedStream().toList();
        ChatStreamEngine selected = ChatEngineSelector.select(engines, properties.getEngine());
        String configured = properties.getEngine();
        if (selected != null && StringUtils.hasText(configured)
                && !selected.name().equalsIgnoreCase(configured.trim())
                && !ChatEngineNames.AGENTSCOPE.equalsIgnoreCase(configured.trim())) {
            log.warn("zeroai.ai.engine={} 未装配，已回落到 {}；可用引擎={}",
                    configured, selected.name(), engines.stream().map(ChatStreamEngine::name).toList());
        }
        return selected;
    }

    /**
     * 解析当前应使用的 Agent 处理器。
     * <p>
     * 只有「配置声明 agentscope」且「Agent 装配确实生效」同时成立才返回实例；
     * 配置写了 agentscope 但装配未生效时降级为单轮通道，并在日志里留下告警，
     * 避免因为配置错误让整个对话入口不可用。
     *
     * @return Agent 处理器；未启用时返回 null
     */
    private AgentChatHandler resolveAgentHandler() {
        AgentChatHandler handler = agentHandlerProvider.getIfAvailable();
        boolean engineIsAgent = ChatEngineNames.AGENTSCOPE.equalsIgnoreCase(properties.getEngine());
        if (engineIsAgent && handler == null) {
            log.warn("zeroai.ai.engine=agentscope 但 Agent 装配未生效，降级为单轮通道");
            return null;
        }
        return engineIsAgent ? handler : null;
    }

    /**
     * 派生会话标识。
     * <p>
     * 刻意不接受前端传入的 sessionId：会话标识一旦可被外部指定，
     * 就必须在每个读取点校验归属，否则会退化成横向越权入口。
     * 匿名会话每次请求独立，不与其他匿名用户共享上下文。
     *
     * @param userId 当前用户 ID，可为 null
     * @return 会话标识
     */
    private String deriveSessionId(Long userId) {
        return userId == null ? "anon-" + UUID.randomUUID() : "u" + userId;
    }
}
