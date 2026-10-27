package com.zeroai.ai.config;

import com.zeroai.ai.knowledge.KnowledgeMode;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 是否需要向量化能力的装配条件。
 * <p>
 * 两个条件同时成立才装配 embedding 客户端：
 * <ol>
 *   <li>{@code mode} 是 {@code vector} 或 {@code hybrid}——纯 BM25 模式不需要向量；</li>
 *   <li>{@code embedding.base-url} 非空——没配地址就不去构造一个注定失败的客户端。</li>
 * </ol>
 * <p>
 * 为什么用代码而不是 {@code @ConditionalOnProperty}：Spring 的属性条件把「值为空字符串」
 * 当作「已配置」，于是 {@code base-url: ${AI_EMBEDDING_BASE_URL:}} 在环境变量缺失时
 * 会匹配上，然后在构造客户端时抛异常。这里必须显式判空。
 */
public class EmbeddingEnabledCondition implements Condition {

    /** 检索模式配置键。 */
    private static final String MODE_KEY = "zeroai.ai.knowledge.mode";

    /** 向量化服务地址配置键。 */
    private static final String BASE_URL_KEY = "zeroai.ai.knowledge.embedding.base-url";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String mode = context.getEnvironment().getProperty(MODE_KEY, KnowledgeMode.BM25.code());
        if (!KnowledgeMode.from(mode).needsEmbedding()) {
            return false;
        }
        String baseUrl = context.getEnvironment().getProperty(BASE_URL_KEY, "");
        return baseUrl != null && !baseUrl.isBlank();
    }
}
