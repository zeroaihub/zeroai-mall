package com.zeroai.ai.engine.langchain4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LangChain4j 装配开关测试。
 * <p>
 * 关注点是「开关是否真的只影响自己」：{@code engine} 不是 langchain4j 时，
 * 该配置必须一行都不执行，不能因为引入了依赖就凭空多出一个模型 Bean。
 * 这里只构造模型对象，不发请求，因此不需要真实密钥。
 */
class LangChain4jEngineAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LangChain4jEngineAutoConfiguration.class))
            .withPropertyValues("zeroai.ai.api-key=test-key-not-real");

    @Test
    @DisplayName("engine=langchain4j 时注册适配器")
    void registersEngineWhenSelected() {
        runner.withPropertyValues("zeroai.ai.enabled=true", "zeroai.ai.engine=langchain4j")
                .run(context -> {
                    assertThat(context).hasSingleBean(LangChain4jChatStreamEngine.class);
                    assertThat(context.getBean(LangChain4jChatStreamEngine.class).name())
                            .isEqualTo("langchain4j");
                });
    }

    @Test
    @DisplayName("engine=spring-ai 时不注册任何 LangChain4j Bean")
    void staysOffWhenSpringAiSelected() {
        runner.withPropertyValues("zeroai.ai.enabled=true", "zeroai.ai.engine=spring-ai")
                .run(context -> assertThat(context).doesNotHaveBean(LangChain4jChatStreamEngine.class));
    }

    @Test
    @DisplayName("默认配置（未写 engine）不注册 LangChain4j Bean")
    void staysOffByDefault() {
        runner.withPropertyValues("zeroai.ai.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(LangChain4jChatStreamEngine.class));
    }

    @Test
    @DisplayName("AI 总开关关闭时，即使 engine=langchain4j 也不装配")
    void staysOffWhenAiDisabled() {
        runner.withPropertyValues("zeroai.ai.enabled=false", "zeroai.ai.engine=langchain4j")
                .run(context -> assertThat(context).doesNotHaveBean(LangChain4jChatStreamEngine.class));
    }

    @Test
    @DisplayName("缺少 api-key 时启动失败，且错误信息指向环境变量注入")
    void failsFastWithoutApiKey() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LangChain4jEngineAutoConfiguration.class))
                .withPropertyValues("zeroai.ai.enabled=true", "zeroai.ai.engine=langchain4j")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "zeroai.ai.api-key 未配置：请通过环境变量 LLM_API_KEY 注入，禁止写入配置文件");
                });
    }

    @Test
    @DisplayName("base-url 不带版本前缀时补 /v1，与 Spring AI 命中同一端点")
    void normalizesBaseUrlWithoutVersion() {
        assertThat(LangChain4jEngineAutoConfiguration.resolveBaseUrl("http://127.0.0.1:3425"))
                .isEqualTo("http://127.0.0.1:3425/v1");
        assertThat(LangChain4jEngineAutoConfiguration.resolveBaseUrl("https://api.deepseek.com/"))
                .isEqualTo("https://api.deepseek.com/v1");
    }

    @Test
    @DisplayName("base-url 已带路径时原样保留，不二次改写")
    void keepsBaseUrlWithPath() {
        assertThat(LangChain4jEngineAutoConfiguration.resolveBaseUrl("https://api.openai.com/v1"))
                .isEqualTo("https://api.openai.com/v1");
        assertThat(LangChain4jEngineAutoConfiguration.resolveBaseUrl("https://gw.example.com/openai/v1/"))
                .isEqualTo("https://gw.example.com/openai/v1");
    }
}
