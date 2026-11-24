package com.zeroai.ai.engine.langchain4j;

import com.zeroai.ai.engine.ChatEngineNames;
import com.zeroai.ai.engine.ChatStreamHandle;
import com.zeroai.ai.engine.ChatStreamListener;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LangChain4j 适配器行为测试。
 * <p>
 * 用假的 {@link StreamingChatModel} 驱动，不触网：这里要验证的是
 * 「端口契约有没有被正确翻译」——消息拼装、增量顺序、兜底文本、取消、异常，
 * 至于模型本身返回什么，不是本层职责。
 */
class LangChain4jChatStreamEngineTest {

    private FakeModel model;
    private List<String> deltas;
    private List<Throwable> errors;
    private AtomicInteger completes;
    private ChatStreamListener listener;

    @BeforeEach
    void setUp() {
        model = new FakeModel();
        deltas = new ArrayList<>();
        errors = new ArrayList<>();
        completes = new AtomicInteger();
        listener = new ChatStreamListener() {
            @Override
            public void onDelta(String delta) {
                deltas.add(delta);
            }

            @Override
            public void onError(Throwable error) {
                errors.add(error);
            }

            @Override
            public void onComplete() {
                completes.incrementAndGet();
            }
        };
    }

    @Test
    @DisplayName("System Prompt 与用户问题一起下发，顺序为 system → user")
    void sendsSystemPromptThenQuestion() {
        new LangChain4jChatStreamEngine(model, "你是导购助手").stream("有手机吗", listener);

        List<ChatMessage> messages = model.captured.messages();
        assertEquals(2, messages.size(), "必须同时带 system 与 user");
        SystemMessage system = assertInstanceOf(SystemMessage.class, messages.get(0));
        assertEquals("你是导购助手", system.text());
        UserMessage user = assertInstanceOf(UserMessage.class, messages.get(1));
        assertEquals("有手机吗", user.singleText());
    }

    @Test
    @DisplayName("System Prompt 为空时只发用户消息，不塞空 system")
    void skipsBlankSystemPrompt() {
        new LangChain4jChatStreamEngine(model, "  ").stream("有手机吗", listener);

        assertEquals(1, model.captured.messages().size());
        assertInstanceOf(UserMessage.class, model.captured.messages().get(0));
    }

    @Test
    @DisplayName("增量按到达顺序转发，最后只 complete 一次")
    void forwardsDeltasInOrder() {
        model.behavior = handler -> {
            handler.onPartialResponse("小米");
            handler.onPartialResponse("手机");
            handler.onPartialResponse("2999");
            handler.onCompleteResponse(response("# ignore"));
        };

        new LangChain4jChatStreamEngine(model, null).stream("有手机吗", listener);

        assertEquals(List.of("小米", "手机", "2999"), deltas);
        assertEquals(1, completes.get());
        assertTrue(errors.isEmpty());
    }

    @Test
    @DisplayName("服务端不给分片时，用最终结果补一段文本，避免空气泡")
    void fallsBackToFinalTextWhenNoPartials() {
        model.behavior = handler -> handler.onCompleteResponse(response("只有最终答案"));

        new LangChain4jChatStreamEngine(model, null).stream("有手机吗", listener);

        assertEquals(List.of("只有最终答案"), deltas);
        assertEquals(1, completes.get());
    }

    @Test
    @DisplayName("取消后不再转发任何回调")
    void cancelStopsCallbacks() {
        // 先拿到 handler，取消之后再手动触发回调，模拟「取消与流式回调并发」
        model.behavior = handler -> {
        };
        ChatStreamHandle handle = new LangChain4jChatStreamEngine(model, null).stream("有手机吗", listener);
        handle.cancel();

        model.handler.onPartialResponse("迟到的增量");
        model.handler.onCompleteResponse(response("迟到"));

        assertTrue(deltas.isEmpty(), "取消后不得再推增量");
        assertEquals(0, completes.get(), "取消后不得再触发完成");
    }

    @Test
    @DisplayName("上游异常转成 onError，不向上抛")
    void forwardsError() {
        IllegalStateException failure = new IllegalStateException("boom");
        model.behavior = handler -> handler.onError(failure);

        new LangChain4jChatStreamEngine(model, null).stream("有手机吗", listener);

        assertEquals(List.of(failure), errors);
        assertEquals(0, completes.get());
    }

    @Test
    @DisplayName("同步抛异常（配置非法等）也走 onError，不穿透控制器")
    void convertsSynchronousExceptionToError() {
        RuntimeException failure = new RuntimeException("baseUrl 非法");
        model.behavior = handler -> {
            throw failure;
        };

        new LangChain4jChatStreamEngine(model, null).stream("有手机吗", listener);

        assertEquals(List.of(failure), errors);
        assertEquals(0, completes.get());
    }

    @Test
    @DisplayName("引擎名与配置取值一致")
    void engineName() {
        assertEquals(ChatEngineNames.LANGCHAIN4J,
                new LangChain4jChatStreamEngine(model, null).name());
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .modelName("test-model")
                .aiMessage(AiMessage.from(text))
                .build();
    }

    /**
     * 假模型：记录请求，按测试指定的行为回调。
     * <p>
     * 只重写两参 {@code chat}：适配器调用的就是它，其余默认方法不会改变测试结果。
     */
    private static final class FakeModel implements StreamingChatModel {

        private ChatRequest captured;
        private StreamingChatResponseHandler handler;
        private Consumer<StreamingChatResponseHandler> behavior =
                h -> h.onCompleteResponse(response("ok"));

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler responseHandler) {
            this.captured = request;
            this.handler = responseHandler;
            behavior.accept(responseHandler);
        }
    }
}
