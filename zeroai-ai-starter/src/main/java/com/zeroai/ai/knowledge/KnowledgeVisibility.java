package com.zeroai.ai.knowledge;

/**
 * 知识文档的可见范围。
 * <p>
 * 区分可见范围的目的不是「多一层配置」，而是把「能进检索索引」和「只属于某个用户」两类数据分开：
 * 知识库只收录对所有用户都成立的规则类文档（{@link #PUBLIC}），
 * 订单、地址、购物车这类用户私有数据走各自的只读工具实时查询，<b>不进知识库</b>。
 * <p>
 * {@link #INTERNAL} 预留给后续的运营内部文档（例如赔付口径、风控规则）：
 * 当前实现<b>不会</b>把 INTERNAL 文档写入公共索引，也不会通过对话检索到。
 */
public enum KnowledgeVisibility {

    /** 对所有访客均可检索的公开知识。 */
    PUBLIC("public"),

    /** 仅内部可见，当前不进入公共索引。 */
    INTERNAL("internal");

    private final String code;

    KnowledgeVisibility(String code) {
        this.code = code;
    }

    /**
     * 索引中存储的字符串值。
     *
     * @return 可见范围编码
     */
    public String code() {
        return code;
    }

    /**
     * 宽松解析可见范围。非法值不抛异常，按 {@link #PUBLIC} 处理之外的保守策略返回 {@link #INTERNAL}，
     * 避免因为写错一个字段就把内部文档暴露成公开知识。
     *
     * @param raw 原始值，可为 null
     * @return 解析结果，无法识别时返回 {@link #INTERNAL}
     */
    public static KnowledgeVisibility from(String raw) {
        if (raw == null || raw.isBlank()) {
            // 缺省视为公开：知识文档是人工编写的白名单内容，缺字段更可能是笔误
            return PUBLIC;
        }
        String normalized = raw.trim().toLowerCase();
        for (KnowledgeVisibility value : values()) {
            if (value.code.equals(normalized)) {
                return value;
            }
        }
        return INTERNAL;
    }
}
