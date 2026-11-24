package com.zeroai.ai.config;

import com.zeroai.ai.agent.AgentChatHandler;
import com.zeroai.ai.engine.ChatStreamEngine;
import com.zeroai.ai.history.AiChatHistory;
import com.zeroai.ai.web.AiChatController;
import com.zeroai.ai.web.AiUserContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * AI Agent 自动装配（与具体模型 SDK 无关的部分）。
 * <p>
 * 只在 {@code zeroai.ai.enabled=true} 时生效，关闭后不注册任何 Bean，应用行为与接入前一致。
 * <p>
 * <b>本类刻意不出现任何模型 SDK 类型。</b>Spring AI 的 ChatModel / ChatClient 在
 * {@code com.zeroai.ai.engine.springai}，LangChain4j 的模型在
 * {@code com.zeroai.ai.engine.langchain4j}，两者都通过 {@link ChatStreamEngine} 端口暴露。
 * 这样新增引擎不需要改动本类，也不会让两套 SDK 的类型混进控制器。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "zeroai.ai", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AiAgentProperties.class)
public class AiAgentAutoConfiguration {

    /**
     * 用户上下文兜底实现。
     * <p>
     * 未提供实现时返回匿名上下文，仅适用于无业务工具对话；
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
     * @param engineProvider       单轮引擎实现集合，按 {@code zeroai.ai.engine} 选择
     * @param properties           AI 配置
     * @param userContext          用户上下文
     * @param agentHandlerProvider Agent 通道处理器提供者；未启用 agentscope 时为空
     * @param chatHistoryProvider  对话历史门面提供者；历史装配未生效时为空
     * @return 控制器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public AiChatController aiChatController(ObjectProvider<ChatStreamEngine> engineProvider,
                                             AiAgentProperties properties,
                                             AiUserContext userContext,
                                             ObjectProvider<AgentChatHandler> agentHandlerProvider,
                                             ObjectProvider<AiChatHistory> chatHistoryProvider) {
        return new AiChatController(engineProvider, properties, userContext,
                agentHandlerProvider, chatHistoryProvider);
    }
}
