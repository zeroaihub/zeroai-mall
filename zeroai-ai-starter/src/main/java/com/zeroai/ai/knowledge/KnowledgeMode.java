package com.zeroai.ai.knowledge;

/**
 * 知识检索模式。
 * <p>
 * 三种模式的差别只在「怎么找片段」，上层 {@link KnowledgeService} 与
 * {@code search_knowledge} 工具完全无感，改一个配置就能切：
 * <ul>
 *   <li>{@link #BM25}：Elasticsearch 倒排索引打分。字面命中，可解释，
 *       对「退款」「运费」这类业务词的精确命中最好，也是当前默认。</li>
 *   <li>{@link #VECTOR}：把文档切块后算向量，按余弦相似度召回。能处理
 *       「钱什么时候退回来」这类不含「退款」二字但语义相同的问法。</li>
 *   <li>{@link #HYBRID}：两路都查，用倒数排名融合（RRF）合并。
 *       字面与语义互补，代价是每次问答多一次 embedding 调用。</li>
 * </ul>
 * <p>
 * <b>为什么不把向量设成默认</b>：向量检索多一条外部依赖（embedding 服务），
 * 它挂了就等于知识库挂了。默认走 BM25 保证「没有任何外部模型服务时，
 * 政策问答依然可用」；向量是可选增强，由部署方按需打开。
 */
public enum KnowledgeMode {

    /** 倒排索引检索（Elasticsearch BM25）。 */
    BM25("bm25"),

    /** 向量语义检索。 */
    VECTOR("vector"),

    /** 混合检索：BM25 + 向量，倒数排名融合。 */
    HYBRID("hybrid");

    private final String code;

    KnowledgeMode(String code) {
        this.code = code;
    }

    /**
     * 配置值。
     *
     * @return 小写标识
     */
    public String code() {
        return code;
    }

    /**
     * 是否需要向量能力（即是否需要 embedding 服务）。
     *
     * @return 向量或混合模式返回 true
     */
    public boolean needsEmbedding() {
        return this != BM25;
    }

    /**
     * 解析配置值。
     * <p>
     * 无法识别时回落 {@link #BM25} 并打日志，不抛异常：检索模式写错不该让服务起不来，
     * 而 BM25 是三种模式里依赖最少、最不可能「静默返回空结果」的那个。
     *
     * @param raw 配置值，可为 null
     * @return 解析结果，永不返回 null
     */
    public static KnowledgeMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return BM25;
        }
        String value = raw.trim().toLowerCase();
        for (KnowledgeMode mode : values()) {
            if (mode.code.equals(value)) {
                return mode;
            }
        }
        return BM25;
    }

    /**
     * 配置值是否可识别。
     *
     * @param raw 配置值
     * @return 可识别返回 true
     */
    public static boolean isKnown(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String value = raw.trim().toLowerCase();
        for (KnowledgeMode mode : values()) {
            if (mode.code.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
