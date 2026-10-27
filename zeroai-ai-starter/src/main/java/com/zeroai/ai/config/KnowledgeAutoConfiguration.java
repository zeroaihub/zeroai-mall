package com.zeroai.ai.config;

import com.zeroai.ai.knowledge.Bm25KnowledgePort;
import com.zeroai.ai.knowledge.KnowledgeEmbedder;
import com.zeroai.ai.knowledge.KnowledgeProperties;
import com.zeroai.ai.knowledge.KnowledgeSearchPort;
import com.zeroai.ai.knowledge.KnowledgeSearchRouter;
import com.zeroai.ai.knowledge.KnowledgeService;
import com.zeroai.ai.knowledge.OpenAiEmbeddingClient;
import com.zeroai.ai.knowledge.VectorKnowledgePort;
import com.zeroai.ai.knowledge.VectorKnowledgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;

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
 * <p>
 * 检索模式由 {@code zeroai.ai.knowledge.mode} 决定，装配结构如下：
 * <pre>
 *   EsKnowledgeStore（入口模块，Bm25KnowledgePort）──┐
 *                                                    ├─→ KnowledgeSearchRouter ─→ KnowledgeService
 *   VectorKnowledgeStore（本模块，VectorKnowledgePort）┘
 * </pre>
 * 两个实现都可以缺失，路由会给出明确的「不可用」而不是空结果。
 * 注意 {@link VectorKnowledgeStore} <b>总是</b>装配（除非整体关闭知识库），
 * 由它自己判断当前模式需不需要建向量索引——这样改配置切模式时不用改装配结构。
 */
@AutoConfiguration
@ConditionalOnExpression("${zeroai.ai.enabled:false} && ${zeroai.ai.knowledge.enabled:true}")
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAutoConfiguration.class);

    /**
     * 向量化客户端。
     * <p>
     * 只在「模式需要向量」且「配了 base-url」时装配。缺失不是错误：
     * 混合模式会自动退化为 BM25，纯向量模式会明确报「检索不可用」。
     *
     * @param properties 知识库配置
     * @return 向量化端口
     */
    @Bean
    @Conditional(EmbeddingEnabledCondition.class)
    @ConditionalOnMissingBean
    public KnowledgeEmbedder knowledgeEmbedder(KnowledgeProperties properties) {
        log.info("启用向量化 model={} dimension={} baseUrl={}",
                properties.getEmbedding().getModel(),
                properties.getEmbedding().getDimension(),
                properties.getEmbedding().getBaseUrl());
        return new OpenAiEmbeddingClient(properties.getEmbedding());
    }

    /**
     * 向量检索实现。
     * <p>
     * {@code embedderProvider} 可能为空（embedding 未配置），此时该实现仍会装配，
     * 但检索会抛「不可用」——这样混合模式能感知到语义分支缺失并回退，
     * 而不是让整个知识库静默消失。
     *
     * @param embedderProvider 向量化端口提供者，可为空
     * @param properties       知识库配置
     * @return 向量检索实现
     */
    @Bean
    @ConditionalOnMissingBean
    public VectorKnowledgeStore vectorKnowledgeStore(ObjectProvider<KnowledgeEmbedder> embedderProvider,
                                                     KnowledgeProperties properties) {
        KnowledgeEmbedder embedder = embedderProvider.getIfAvailable();
        if (properties.resolveMode().needsEmbedding() && embedder == null) {
            log.warn("检索模式为 {} 但未配置 embedding.base-url，向量分支将不可用", properties.resolveMode().code());
        }
        return new VectorKnowledgeStore(embedder, properties);
    }

    /**
     * 检索路由。
     * <p>
     * 标记 {@code @Primary}：容器里可能同时存在 BM25 与向量两个
     * {@link KnowledgeSearchPort}，门面注入时应当拿到路由而不是随便一个实现。
     *
     * @param bm25Provider     BM25 实现提供者，可为空
     * @param vectorProvider   向量实现提供者，可为空
     * @param properties       知识库配置
     * @return 检索路由
     */
    @Bean
    @Primary
    public KnowledgeSearchRouter knowledgeSearchRouter(ObjectProvider<Bm25KnowledgePort> bm25Provider,
                                                       ObjectProvider<VectorKnowledgePort> vectorProvider,
                                                       KnowledgeProperties properties) {
        return new KnowledgeSearchRouter(bm25Provider.getIfAvailable(), vectorProvider.getIfAvailable(), properties);
    }

    /**
     * 注册知识检索门面。
     *
     * @param portProvider 检索实现提供者（注入 {@code @Primary} 的路由）
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
