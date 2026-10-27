package com.zeroai.ai.web;

import com.zeroai.ai.agent.AgentChatHandler;
import com.zeroai.ai.config.AiAgentProperties;
import com.zeroai.ai.history.AiChatHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * 通用流式对话入口。
 * <p>
 * 由 starter 提供，业务项目无需重复实现；端点路径可通过 {@code zeroai.ai.chat-path} 调整。
 * 本类只负责「选择引擎 + 把输出转成 SSE 事件」，不含任何业务逻辑，也不做鉴权——
 * 鉴权由接入方在自身拦截器中完成，用户身份通过 {@link AiUserContext} 注入。
 * <p>
 * 两条通道对外契约完全一致，前端无需感知差异：
 * <ul>
 *   <li>{@code zeroai.ai.engine=spring-ai}（默认）：单轮无工具问答，走 Spring AI ChatClient。</li>
 *   <li>{@code zeroai.ai.engine=agentscope}：带只读商城工具的 Agent，走 AgentScope Harness。</li>
 * </ul>
 * 注意：本端点必须在入口线程取到用户快照，异步回调中不再访问 ThreadLocal。
 * <p>
 * 本类所在包 {@code com.zeroai.ai} 落在宿主项目的组件扫描路径内，会被直接扫描注册，
 * 从而绕过 {@code AiAgentAutoConfiguration} 上的开关。因此这里必须重复声明同一个条件，
 * 否则 {@code zeroai.ai.enabled=false} 时仍会因缺少 ChatClient 而启动失败。
 */
@RestController
@ConditionalOnProperty(prefix = "zeroai.ai", name = "enabled", havingValue = "true")
public class AiChatController {

    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);

    /** Agent 引擎标识，与 {@code zeroai.ai.engine} 取值一致。 */
    private static final String ENGINE_AGENTSCOPE = "agentscope";

    private final ChatClient chatClient;
    private final AiAgentProperties properties;
    private final AiUserContext userContext;

    /**
     * Agent 通道处理器。
     * <p>
     * 用 {@link ObjectProvider} 而非直接注入：{@code engine=spring-ai} 时 Agent 装配不生效，
     * 直接注入会因缺少 Bean 导致启动失败。取不到即代表当前未启用 Agent 通道。
     */
    private final ObjectProvider<AgentChatHandler> agentHandlerProvider;

    /**
     * 对话历史门面。用 {@link ObjectProvider} 取，取不到时按「不记录历史」处理。
     */
    private final ObjectProvider<AiChatHistory> chatHistoryProvider;

    public AiChatController(ChatClient chatClient,
                            AiAgentProperties properties,
                            AiUserContext userContext,
                            ObjectProvider<AgentChatHandler> agentHandlerProvider,
                            ObjectProvider<AiChatHistory> chatHistoryProvider) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.userContext = userContext;
        this.agentHandlerProvider = agentHandlerProvider;
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

        if (agentHandler != null) {
            log.info("AI 对话开始（Agent 通道），userId={}, sessionId={}, questionLength={}",
                    userId, sessionId, question.length());
            agentHandler.handle(sink, userId, sessionId, question);
            return sink.emitter();
        }

        log.info("AI 对话开始（单轮通道），userId={}, questionLength={}", userId, question.length());
        streamWithChatClient(sink, userId, sessionId, question);
        return sink.emitter();
    }

    /**
     * 单轮问答通道：无工具、无会话，行为与接入 Agent 前完全一致。
     * <p>
     * 仍然记录历史：即使引擎切回单轮通道，用户重进页面也该看到聊过什么。
     *
     * @param sink      SSE 出口
     * @param userId    用户 ID，仅用于日志与历史归属
     * @param sessionId 会话标识，仅用于历史归档
     * @param question  用户问题
     */
    private void streamWithChatClient(SseSink sink, Long userId, String sessionId, String question) {
        AiChatHistory history = chatHistoryProvider.getIfAvailable();
        AiChatHistory.Turn turn = history == null ? null : history.begin(userId, sessionId, question);

        chatClient.prompt()
                .user(question)
                .stream()
                .content()
                .subscribe(
                        delta -> {
                            if (turn != null) {
                                turn.delta(delta);
                            }
                            sink.send(AiEvent.of(AiEventType.MESSAGE_DELTA, Map.of("delta", delta)));
                        },
                        error -> {
                            log.error("AI 流式对话失败，userId={}", userId, error);
                            if (turn != null) {
                                turn.fail("AI_STREAM_ERROR", 0);
                            }
                            sink.send(AiEvent.of(AiEventType.ERROR,
                                    Map.of("code", "AI_STREAM_ERROR", "message", "服务繁忙，请稍后再试")));
                            sink.complete();
                        },
                        () -> {
                            if (turn != null) {
                                turn.success(0);
                            }
                            sink.send(AiEvent.of(AiEventType.DONE, Map.of("steps", 0)));
                            sink.complete();
                        });
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
        boolean engineIsAgent = ENGINE_AGENTSCOPE.equalsIgnoreCase(properties.getEngine());
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
