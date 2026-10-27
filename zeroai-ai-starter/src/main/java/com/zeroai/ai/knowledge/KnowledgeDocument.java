package com.zeroai.ai.knowledge;

import java.util.List;

/**
 * 一篇知识文档。
 * <p>
 * 字段直接对应知识库验收要求里的「sourceId、更新时间、版本、可见范围」：
 * 引用来源靠 {@code sourceId}，答案是否过期靠 {@code updatedAt} 与 {@code version}，
 * 权限边界靠 {@code visibility}。
 * <p>
 * 文档内容只描述<b>规则与政策</b>，不承载商品实时数据（价格、库存走商品工具），
 * 也不承载任何用户私有数据（订单、地址走用户级工具）。
 *
 * @param sourceId   稳定标识，同时作为 ES 文档 ID；同一 sourceId 内容更新即覆盖
 * @param title      文档标题，回答里给用户看的引用名
 * @param category   分类，用于检索加权与评测分组
 * @param version    文档版本，内容变更时递增；仅用于排查，不参与检索打分
 * @param updatedAt  最后更新时间（ISO 日期），回答引用时会带上
 * @param visibility 可见范围，公共索引只收 {@link KnowledgeVisibility#PUBLIC}
 * @param tags       检索关键词，人工补充同义词（如「包邮」「运费」），提升召回
 * @param content    正文，纯文本
 */
public record KnowledgeDocument(String sourceId,
                               String title,
                               String category,
                               int version,
                               String updatedAt,
                               KnowledgeVisibility visibility,
                               List<String> tags,
                               String content) {

    /**
     * 归一化：tags 为 null 时返回空列表，避免调用方到处判空。
     *
     * @return 规范化后的文档
     */
    public KnowledgeDocument normalized() {
        List<String> safeTags = tags == null ? List.of() : tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .toList();
        return new KnowledgeDocument(sourceId, title, category, version, updatedAt, visibility, safeTags, content);
    }

    /**
     * 是否可进入公共检索索引。
     *
     * @return 公开文档返回 true
     */
    public boolean isPublic() {
        return visibility == KnowledgeVisibility.PUBLIC;
    }
}
