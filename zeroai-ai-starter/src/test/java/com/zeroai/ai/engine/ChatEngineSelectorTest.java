package com.zeroai.ai.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 引擎选择规则测试。
 * <p>
 * 这里覆盖的都是「配置写错会静默降级」的分支：名字拼错、大小写不一致、实现没装配。
 * 这类问题在运行期只会表现为「回答风格不对」，不看日志很难发现，
 * 因此必须在没有 Spring 上下文的情况下就能直接断言。
 */
class ChatEngineSelectorTest {

    private static ChatStreamEngine engine(String name) {
        return new ChatStreamEngine() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ChatStreamHandle stream(String question, ChatStreamListener listener) {
                return ChatStreamHandle.noop();
            }
        };
    }

    @Test
    @DisplayName("按配置名精确命中，且大小写不敏感")
    void selectsConfiguredEngine() {
        ChatStreamEngine springAi = engine(ChatEngineNames.SPRING_AI);
        ChatStreamEngine langChain4j = engine(ChatEngineNames.LANGCHAIN4J);
        List<ChatStreamEngine> engines = List.of(springAi, langChain4j);

        assertSame(langChain4j, ChatEngineSelector.select(engines, "langchain4j"));
        assertSame(langChain4j, ChatEngineSelector.select(engines, "LangChain4J"));
        assertSame(langChain4j, ChatEngineSelector.select(engines, "  langchain4j  "));
        assertSame(springAi, ChatEngineSelector.select(engines, "spring-ai"));
    }

    @Test
    @DisplayName("配置写错时回落 spring-ai，而不是让对话不可用")
    void fallsBackToSpringAiOnUnknownName() {
        ChatStreamEngine springAi = engine(ChatEngineNames.SPRING_AI);
        ChatStreamEngine langChain4j = engine(ChatEngineNames.LANGCHAIN4J);

        assertSame(springAi,
                ChatEngineSelector.select(List.of(langChain4j, springAi), "langchain-4j-typo"));
    }

    @Test
    @DisplayName("配置为空时用 spring-ai 兜底")
    void fallsBackOnBlankConfig() {
        ChatStreamEngine springAi = engine(ChatEngineNames.SPRING_AI);
        List<ChatStreamEngine> engines = List.of(springAi);

        assertSame(springAi, ChatEngineSelector.select(engines, null));
        assertSame(springAi, ChatEngineSelector.select(engines, "   "));
    }

    @Test
    @DisplayName("既没有配置项也没有 spring-ai 时，取第一个可用实现")
    void usesFirstEngineWhenSpringAiMissing() {
        ChatStreamEngine langChain4j = engine(ChatEngineNames.LANGCHAIN4J);

        assertSame(langChain4j, ChatEngineSelector.select(List.of(langChain4j), "spring-ai"));
    }

    @Test
    @DisplayName("一个实现都没有时返回 null，由调用方报错")
    void returnsNullWhenNoEngine() {
        assertNull(ChatEngineSelector.select(List.of(), "langchain4j"));
        assertNull(ChatEngineSelector.select(null, "langchain4j"));
    }

    @Test
    @DisplayName("引擎名常量与配置取值保持一致")
    void engineNamesMatchConfigValues() {
        // 常量被控制器与配置注释引用，飘了就等于配置失效
        assertEquals("spring-ai", ChatEngineNames.SPRING_AI);
        assertEquals("langchain4j", ChatEngineNames.LANGCHAIN4J);
        assertEquals("agentscope", ChatEngineNames.AGENTSCOPE);
    }
}
