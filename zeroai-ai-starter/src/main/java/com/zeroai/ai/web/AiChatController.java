package com.zeroai.ai.web;

import com.zeroai.ai.config.AiAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通用流式对话入口。
 * <p>
 * 由 starter 提供，业务项目无需重复实现；端点路径可通过 {@code zeroai.ai.chat-path} 调整。
 * 本类只负责「把模型输出转成 SSE 事件」，不含任何业务逻辑，也不做鉴权——
 * 鉴权由接入方在自身拦截器中完成，用户身份通过 {@link AiUserContext} 注入。
 * <p>
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

    private final ChatClient chatClient;
    private final AiAgentProperties properties;
    private final AiUserContext userContext;

    public AiChatController(ChatClient chatClient, AiAgentProperties properties, AiUserContext userContext) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.userContext = userContext;
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
        SseEmitter emitter = new SseEmitter(properties.getSseTimeoutMs());
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(e -> closed.set(true));

        if (!StringUtils.hasText(question)) {
            emit(emitter, closed, AiEvent.of(AiEventType.ERROR,
                    Map.of("code", "EMPTY_QUESTION", "message", "请输入你想咨询的内容")));
            finish(emitter, closed);
            return emitter;
        }

        // 入口线程取用户快照：异步回调里 ThreadLocal 已失效，不能再取
        Long userId = userContext.currentUserId();
        log.info("AI 对话开始，userId={}, questionLength={}", userId, question.length());

        chatClient.prompt()
                .user(question)
                .stream()
                .content()
                .subscribe(
                        delta -> emit(emitter, closed, AiEvent.of(AiEventType.MESSAGE_DELTA, Map.of("delta", delta))),
                        error -> {
                            log.error("AI 流式对话失败，userId={}", userId, error);
                            emit(emitter, closed, AiEvent.of(AiEventType.ERROR,
                                    Map.of("code", "AI_STREAM_ERROR", "message", "服务繁忙，请稍后再试")));
                            finish(emitter, closed);
                        },
                        () -> {
                            emit(emitter, closed, AiEvent.of(AiEventType.DONE, Map.of("steps", 0)));
                            finish(emitter, closed);
                        });
        return emitter;
    }

    /**
     * 发送单个 SSE 事件。连接已关闭时静默丢弃，避免刷日志。
     */
    private void emit(SseEmitter emitter, AtomicBoolean closed, AiEvent event) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
            log.debug("SSE 发送失败，连接可能已关闭：{}", e.getMessage());
        }
    }

    /**
     * 结束连接。重复调用不抛异常，防止超时与正常完成竞争导致报错。
     */
    private void finish(SseEmitter emitter, AtomicBoolean closed) {
        if (closed.getAndSet(true)) {
            return;
        }
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            log.debug("SSE 连接已结束：{}", e.getMessage());
        }
    }
}
