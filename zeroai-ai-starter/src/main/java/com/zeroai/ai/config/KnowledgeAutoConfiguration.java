package com.zeroai.ai.config;

import com.zeroai.ai.knowledge.KnowledgeProperties;
import com.zeroai.ai.knowledge.KnowledgeSearchPort;
import com.zeroai.ai.knowledge.KnowledgeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 知识库自动装配。
 * <p>
 * 生效条件：{@code zeroai.ai.enabled=true} 且 {@code zeroai.ai.knowledge.enabled=true}（默认开启）。
 * 知识库是可选扩展：把 {@code zeroai.ai.knowledge.enabled} 设为 false 即可整体回退，
 * 此时不注册任何 Bean，Agent 工具面里也不会出现 {@code search_knowledge}。
 * <p>
 * 与对话历史一样采用「端口 + 门面」两层：存储实现依赖具体中间件（ES），
 * 落在入口模块；本模块只声明端口。入口模块没有实现时门面仍然可用，
 * 检索会返回 {@code UNAVAILABLE} 让模型说「查不到」，而不是让整个对话失败。
 */
@AutoConfiguration
@ConditionalOnExpression("${zeroai.ai.enabled:false} && ${zeroai.ai.knowledge.enabled:true}")
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeAutoConfiguration {

    /**
     * 注册知识检索门面。
     *
     * @param portProvider 检索实现提供者，可为空
     * @param properties   知识库配置
     * @return 检索门面
     */
    @Bean
    @ConditionalOnMissingBean
    public KnowledgeService knowledgeService(ObjectProvider<KnowledgeSearchPort> portProvider,
                                             KnowledgeProperties properties) {
        return new KnowledgeService(portProvider.getIfAvailable(), properties);
    }
}
