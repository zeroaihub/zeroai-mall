package com.zeroai.ai.knowledge;

/**
 * 向量检索实现标记。
 * <p>
 * 与 {@link Bm25KnowledgePort} 成对出现，理由见该接口注释：让路由按类型取实现，
 * 避免注入 {@code List<KnowledgeSearchPort>} 时把自己也注入进来。
 * <p>
 * 实现方有两个，按 {@code zeroai.ai.knowledge.vector.store} 二选一装配：
 * <ul>
 *   <li>{@link VectorKnowledgeStore}：进程内索引，默认，零新增基础设施；</li>
 *   <li>{@code PgVectorKnowledgeStore}（入口模块）：PG 持久化 + 增量同步。</li>
 * </ul>
 * 两者不能同时存在：路由用 {@code ObjectProvider.getIfAvailable()} 取候选，
 * 多一个实现会直接抛 {@code NoUniqueBeanDefinitionException}。
 */
public interface VectorKnowledgePort extends KnowledgeSearchPort {

    @Override
    default KnowledgeMode mode() {
        return KnowledgeMode.VECTOR;
    }
}
