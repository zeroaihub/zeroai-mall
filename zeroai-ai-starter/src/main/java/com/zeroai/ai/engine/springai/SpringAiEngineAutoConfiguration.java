package com.zeroai.ai.engine.springai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.zeroai.ai.config.AiAgentProperties;
import com.zeroai.ai.engine.ChatStreamEngine;
import com.zeroai.ai.prompt.PromptLoader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * Spring AI 通道装配。
 * <p>
 * <b>装配范围是本包 + 本包产生的 Bean</b>：ChatModel、ChatClient、Spring AI 适配器都在这里，
 * 模块其他位置不再直接构造 Spring AI 对象。这样 LangChain4j 通道加入后，
 * 两套 SDK 的装配入口各自唯一，排查问题时不需要在两个包之间来回跳。
 * <p>
 * 为什么即使 {@code engine=langchain4j} 也照常装配：ChatModel 构造只建 HTTP 客户端、不发请求，
 * 成本可忽略；保留它作为兜底，使「LangChain4j 特有的装配问题」不至于让对话整体不可用。
 * 注意 apiKey / baseUrl 是两套引擎共用的：若共用配置本身非法，两边都会启动失败，
 * 这里不构成「配置错了也能用」的保证，只覆盖某套 SDK 自身构造失败的情况。
 * <p>
 * 刻意不使用 Spring AI 的模型自动装配：一是避免未配置 api-key 时启动失败，
 * 二是接入方可以自行覆盖 {@link ChatModel} / {@link ChatClient} 而不与本模块冲突。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "zeroai.ai", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AiAgentProperties.class)
public class SpringAiEngineAutoConfiguration {

    /**
     * 构建 ChatModel。当前只实现 OpenAI 兼容通道（DeepSeek / 通义等）。
     *
     * @param properties AI 配置
     * @return ChatModel 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatModel aiChatModel(AiAgentProperties properties) {
        return buildOpenAiChatModel(properties);
    }

    /**
     * 构建 ChatClient。System Prompt 从 classpath 外置文件加载，
     * 业务代码中不应出现任何 AI 基建细节。
     *
     * @param aiChatModel 模型实例
     * @param properties  AI 配置
     * @return ChatClient 单例
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatClient aiChatClient(ChatModel aiChatModel, AiAgentProperties properties) {
        return ChatClient.builder(aiChatModel)
                .defaultSystem(PromptLoader.load(properties.getSystemPrompt()))
                .build();
    }

    /**
     * Spring AI 单轮引擎。始终注册：既是默认引擎，也是 LangChain4j 不可用时的兜底。
     *
     * @param chatClient 客户端
     * @return 引擎实现
     */
    @Bean
    public ChatStreamEngine springAiChatStreamEngine(ChatClient chatClient) {
        return new SpringAiChatStreamEngine(chatClient);
    }

    private ChatModel buildOpenAiChatModel(AiAgentProperties properties) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException(
                    "zeroai.ai.api-key 未配置：请通过环境变量 LLM_API_KEY 注入，禁止写入配置文件");
        }
        // Spring AI 2.0 移除了 OpenAiApi，改用官方 OpenAI Java SDK 构建客户端
        OpenAIClient openAiClient = OpenAIOkHttpClient.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .build();
        // 必须同时注入异步客户端：SDK 的 OpenAiChatModel.Builder 在只给同步客户端时，
        // 仍会走 OpenAiSetup 自行构造 async 客户端，并因拿不到 apiKey 抛
        // IllegalStateException("At least one credential source must be specified")。
        OpenAIClientAsync openAiClientAsync = OpenAIOkHttpClientAsync.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.getModel())
                .temperature(properties.getTemperature())
                .maxTokens(properties.getMaxTokens())
                .build();
        return OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClientAsync)
                .options(options)
                .build();
    }
}
