package com.zeroai.ai.config;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.zeroai.ai.agent.AgentChatHandler;
import com.zeroai.ai.prompt.PromptLoader;
import com.zeroai.ai.web.AiChatController;
import com.zeroai.ai.web.AiUserContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * AI Agent 自动装配。
 * <p>
 * 只在 {@code zeroai.ai.enabled=true} 时生效，关闭后不注册任何 Bean，应用行为与接入前一致。
 * 这里刻意不使用 Spring AI 的模型自动装配：一是避免未配置 api-key 时启动失败，
 * 二是接入方可以自行覆盖 {@link ChatModel} / {@link ChatClient} 而不与本模块冲突。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "zeroai.ai", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AiAgentProperties.class)
public class AiAgentAutoConfiguration {

    /**
     * 构建 ChatModel。当前只实现 OpenAI 兼容通道（DeepSeek / 通义等），
     * 本地 Ollama 兜底通道作为独立能力后续追加，避免双模型注入歧义。
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
     * 构建 ChatClient。System Prompt 从 classpath 外置文件加载，Advisor 链在此统一编排，
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
     * 用户上下文兜底实现。
     * <p>
     * 未提供实现时返回匿名上下文，仅适用于 P1 阶段的无业务工具对话；
     * 一旦接入工具，接入方必须提供读取自身鉴权上下文的实现，否则无法满足越权防护要求。
     *
     * @return 兜底用户上下文
     */
    @Bean
    @ConditionalOnMissingBean
    public AiUserContext aiUserContext() {
        return () -> null;
    }

    /**
     * 注册通用流式对话控制器。
     *
     * @param aiChatClient         ChatClient
     * @param properties           AI 配置
     * @param userContext          用户上下文
     * @param agentHandlerProvider Agent 通道处理器提供者；未启用 agentscope 时为空
     * @return 控制器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public AiChatController aiChatController(ChatClient aiChatClient, AiAgentProperties properties,
                                             AiUserContext userContext,
                                             ObjectProvider<AgentChatHandler> agentHandlerProvider) {
        return new AiChatController(aiChatClient, properties, userContext, agentHandlerProvider);
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
        // 该缺陷此前未被发现，是因为 AI 开关一直关闭、这条装配路径从未被执行过。
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
