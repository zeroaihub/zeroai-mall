package com.zeroai.ai.knowledge;

/**
 * 倒排索引（BM25）检索实现标记。
 * <p>
 * 存在的唯一理由是让 {@link KnowledgeSearchRouter} 能<b>按类型</b>拿到两种实现。
 * 如果路由直接注入 {@code List<KnowledgeSearchPort>}，这个列表会包含路由自己，
 * 形成构造器循环依赖。用两个标记接口把「谁是 BM25、谁是向量」变成编译期事实，
 * 比按 bean 名字匹配或反射查注解稳得多。
 * <p>
 * 注意：这是可选能力，容器里可能一个实现都没有（入口模块未接入 ES）。
 * 路由必须容忍缺失，并在缺失时给出明确的「不可用」而不是空结果。
 */
public interface Bm25KnowledgePort extends KnowledgeSearchPort {

    @Override
    default KnowledgeMode mode() {
        return KnowledgeMode.BM25;
    }
}
