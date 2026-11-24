package com.zeroai.ai.engine.springai;

import com.zeroai.ai.engine.ChatEngineNames;
import com.zeroai.ai.engine.ChatStreamEngine;
import com.zeroai.ai.engine.ChatStreamHandle;
import com.zeroai.ai.engine.ChatStreamListener;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Spring AI 单轮引擎：把 {@link ChatClient} 的流式输出适配成引擎端口。
 * <p>
 * <b>本类是 Spring AI 的唯一出口。</b>端口之外的代码只能看到
 * {@link ChatStreamEngine}，不应再直接引用 {@code ChatClient} 或 Reactor 类型；
 * 换引擎时只替换本包，控制器不动。
 * <p>
 * 取消语义：基于 Reactor 订阅，{@code dispose()} 会立刻断开订阅并停止拉取增量。
 */
public class SpringAiChatStreamEngine implements ChatStreamEngine {

    private final ChatClient chatClient;

    /**
     * @param chatClient Spring AI 客户端，构造时已带默认 System Prompt
     */
    public SpringAiChatStreamEngine(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String name() {
        return ChatEngineNames.SPRING_AI;
    }

    @Override
    public ChatStreamHandle stream(String question, ChatStreamListener listener) {
        /*
         * 构造/订阅阶段同步抛异常时转成 onError 回调：
         * 与 LangChain4j 适配器保持一致——异常不穿透控制器，前端始终收到 SSE error 事件。
         * 订阅本身可能同步终止，但 Reactor 会把错误路由到 onError consumer，
         * 不会从 subscribe() 同步抛出，因此这里只防构造阶段的异常。
         */
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        try {
            Disposable subscription = chatClient.prompt()
                    .user(question)
                    .stream()
                    .content()
                    .subscribe(listener::onDelta, listener::onError, listener::onComplete);
            subscriptionRef.set(subscription);
        } catch (RuntimeException e) {
            listener.onError(e);
            return ChatStreamHandle.noop();
        }
        return () -> {
            Disposable current = subscriptionRef.get();
            if (current != null && !current.isDisposed()) {
                current.dispose();
            }
        };
    }
}
