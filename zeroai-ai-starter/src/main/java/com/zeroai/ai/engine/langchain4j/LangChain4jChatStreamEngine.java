package com.zeroai.ai.engine.langchain4j;

import com.zeroai.ai.engine.ChatEngineNames;
import com.zeroai.ai.engine.ChatStreamEngine;
import com.zeroai.ai.engine.ChatStreamHandle;
import com.zeroai.ai.engine.ChatStreamListener;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LangChain4j 单轮引擎：把 {@link StreamingChatModel} 适配成引擎端口。
 * <p>
 * <b>本包是 LangChain4j 的唯一出口。</b>端口之外的代码不得引用
 * {@code dev.langchain4j.*}，否则两套 SDK 又会顺着一处引用扩散开。
 * <p>
 * 与 Spring AI 适配器的差异（都是 SDK 层面的客观差异，不是实现取舍）：
 * <ul>
 *   <li>System Prompt 在这里拼进消息列表，而 Spring AI 是在构造 ChatClient 时用 defaultSystem 注入。
 *       两者读同一个 {@code zeroai.ai.system-prompt} 文件，提示词内容保持一致。</li>
 *   <li>1.24.0 的流式 API 没有 abort/close 入口，{@link ChatStreamHandle#cancel()} 只能停止转发，
 *       上游 HTTP 流会继续跑到结束。对成本敏感的场景要知道这一点，见 ADR-002。</li>
 * </ul>
 */
public class LangChain4jChatStreamEngine implements ChatStreamEngine {

    private final StreamingChatModel model;

    /** System Prompt；为空时不注入，退化为纯用户消息（与 Spring AI 的 defaultSystem 行为一致）。 */
    private final String systemPrompt;

    /**
     * @param model        流式模型，生产环境用 OpenAI 兼容实现
     * @param systemPrompt System Prompt 全文，可为空
     */
    public LangChain4jChatStreamEngine(StreamingChatModel model, String systemPrompt) {
        this.model = model;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public String name() {
        return ChatEngineNames.LANGCHAIN4J;
    }

    @Override
    public ChatStreamHandle stream(String question, ChatStreamListener listener) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        // 记录是否真的收到过增量：部分 OpenAI 兼容服务端只回最终结果、不给分片，
        // 这时若直接 onComplete，用户会看到空气泡
        AtomicBoolean sawDelta = new AtomicBoolean(false);
        ChatRequest request = ChatRequest.builder().messages(buildMessages(question)).build();

        StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (cancelled.get() || partialResponse == null || partialResponse.isEmpty()) {
                    return;
                }
                sawDelta.set(true);
                listener.onDelta(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                if (cancelled.get()) {
                    return;
                }
                /*
                 * 兜底：没有分片时用最终结果补一段文本。
                 * 不补的话前端只会收到 done，气泡是空的，用户会以为回答丢了。
                 */
                if (!sawDelta.get() && completeResponse != null && completeResponse.aiMessage() != null) {
                    String text = completeResponse.aiMessage().text();
                    if (StringUtils.hasText(text)) {
                        listener.onDelta(text);
                    }
                }
                listener.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                if (cancelled.get()) {
                    return;
                }
                listener.onError(error);
            }
        };

        try {
            model.chat(request, handler);
        } catch (RuntimeException e) {
            // 构造/提交阶段就失败（例如 baseUrl 非法）：转成回调，不把异常穿透给控制器
            if (!cancelled.get()) {
                listener.onError(e);
            }
        }
        return () -> cancelled.set(true);
    }

    /**
     * 组装消息：System Prompt 在前，用户问题在后。
     *
     * @param question 用户问题
     * @return 消息列表，至少包含一条用户消息
     */
    private List<ChatMessage> buildMessages(String question) {
        List<ChatMessage> messages = new ArrayList<>(2);
        if (StringUtils.hasText(systemPrompt)) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(question));
        return messages;
    }
}
