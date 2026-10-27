package com.zeroai.ai.knowledge;

/**
 * 一条检索命中。
 * <p>
 * 只保留「回答需要引用什么」的最小字段：来源标识、标题、版本、更新时间、片段。
 * 刻意不下发整篇文档正文与内部打分细则：片段足够回答问题，全文下发只会让上下文变贵，
 * 也让模型有更多机会把无关段落当事实。
 *
 * @param sourceId  来源标识，回答引用时必须原样给出
 * @param title     文档标题
 * @param category  分类
 * @param version   文档版本
 * @param updatedAt 最后更新时间
 * @param snippet   命中片段，已清洗与截断
 * @param score     检索得分，仅用于排序与排障，不向用户展示
 */
public record KnowledgeHit(String sourceId,
                           String title,
                           String category,
                           int version,
                           String updatedAt,
                           String snippet,
                           double score) {
}
