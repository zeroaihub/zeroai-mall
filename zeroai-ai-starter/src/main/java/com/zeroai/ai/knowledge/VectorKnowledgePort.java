package com.zeroai.ai.knowledge;

/**
 * 向量检索实现标记。
 * <p>
 * 与 {@link Bm25KnowledgePort} 成对出现，理由见该接口注释：让路由按类型取实现，
 * 避免注入 {@code List<KnowledgeSearchPort>} 时把自己也注入进来。
 * <p>
 * 实现方通常是 {@link VectorKnowledgeStore}——它只在
 * {@code zeroai.ai.knowledge.mode} 不是 {@code bm25} 时才会被装配，
 * 因为构建向量索引需要调用外部 embedding 服务，纯 BM25 模式下不该产生这笔开销。
 */
public interface VectorKnowledgePort extends KnowledgeSearchPort {

    @Override
    default KnowledgeMode mode() {
        return KnowledgeMode.VECTOR;
    }
}
