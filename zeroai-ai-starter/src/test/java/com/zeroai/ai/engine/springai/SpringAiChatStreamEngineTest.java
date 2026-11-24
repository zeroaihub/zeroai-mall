package com.zeroai.ai.engine.springai;

import com.zeroai.ai.engine.ChatStreamHandle;
import com.zeroai.ai.engine.ChatStreamListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spring AI 适配器的一致性测试。
 * <p>
 * 这里只覆盖「异常不穿透控制器」这一条：LangChain4j 适配器已有同类用例，
 * 两套实现对同一端口的异常语义必须一致，否则切换引擎时前端会收到不同形态的错误。
 */
class SpringAiChatStreamEngineTest {

    @Test
    @DisplayName("构造阶段同步抛异常时转成 onError，不向上抛")
    void convertsSynchronousExceptionToError() {
        ChatClient chatClient = mock(ChatClient.class);
        RuntimeException failure = new RuntimeException("prompt 构造失败");
        when(chatClient.prompt()).thenThrow(failure);
        List<Throwable> errors = new ArrayList<>();
        AtomicInteger completes = new AtomicInteger();

        ChatStreamHandle handle = new SpringAiChatStreamEngine(chatClient)
                .stream("有手机吗", new ChatStreamListener() {
                    @Override
                    public void onDelta(String delta) {
                        // 构造即失败，不应产生增量
                    }

                    @Override
                    public void onError(Throwable error) {
                        errors.add(error);
                    }

                    @Override
                    public void onComplete() {
                        completes.incrementAndGet();
                    }
                });

        assertEquals(List.of(failure), errors);
        assertEquals(0, completes.get());
        assertNotNull(handle, "异常路径也必须返回可取消句柄");
        handle.cancel();
    }
}
