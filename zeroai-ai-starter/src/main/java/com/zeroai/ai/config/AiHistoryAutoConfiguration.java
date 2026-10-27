package com.zeroai.ai.config;

import com.zeroai.ai.agent.AgentScopeAutoConfiguration;
import com.zeroai.ai.history.AiChatHistory;
import com.zeroai.ai.history.AiChatHistoryStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 对话历史自动装配。
 * <p>
 * 只在 {@code zeroai.ai.enabled=true} 时生效，且<b>不要求</b>存在存储实现：
 * 历史是旁路能力，入口模块没提供 {@link AiChatHistoryStore} 时门面退化为空操作，
 * 对话与提议链路完全不受影响。
 * <p>
 * 之所以把「存储 SPI」和「门面」拆开：存储实现依赖具体中间件（如 ES 客户端），
 * 而本模块定位是可复用的 AI 能力包，不能反向绑定某个中间件。
 *
 * @see AiChatHistory
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "zeroai.ai", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AiHistoryProperties.class)
public class AiHistoryAutoConfiguration {

    /**
     * 注册历史门面。
     *
     * @param storeProvider 存储实现提供者，可为空
     * @param properties    历史配置
     * @return 历史门面
     */
    @Bean
    @ConditionalOnMissingBean
    public AiChatHistory aiChatHistory(ObjectProvider<AiChatHistoryStore> storeProvider,
                                       AiHistoryProperties properties) {
        return new AiChatHistory(storeProvider.getIfAvailable(), properties);
    }
}
