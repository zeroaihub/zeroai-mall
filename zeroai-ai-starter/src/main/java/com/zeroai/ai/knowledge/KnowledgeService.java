package com.zeroai.ai.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识检索门面。
 * <p>
 * 把「检索」这件事收敛成三个可测试的判断：能不能查、查到的算不算答案、查不到时怎么表达。
 * 上层（工具、HTTP 接口）只拿到 {@link Result}，不需要知道底下是 ES 还是别的引擎。
 * <p>
 * 设计上的三个刻意选择：
 * <ol>
 *   <li><b>检索失败与无结果都返回结构化状态</b>，不抛异常、不返回空列表了事。
 *       「检索服务挂了」和「知识库里确实没有这条政策」对模型意味着完全不同的回答策略：
 *       前者应建议稍后再试，后者必须直接说不知道。</li>
 *   <li><b>片段清洗与截断在这里做</b>，而不是在存储实现里做。
 *       存储负责检索，门面负责「能不能安全地给模型看」，职责不混。</li>
 *   <li><b>不缓存</b>。政策文档是低频变更的小语料，缓存收益极小，
 *       却会引入「文档更新后回答还是旧口径」的问题。</li>
 * </ol>
 */
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    /**
     * 检索前剔除的噪声词。按长度倒序匹配，避免「在哪里」被「哪里」提前截断。
     */
    private static final List<String> NOISE_PHRASES = List.of(
            "想问一下", "请问一下", "帮我看下", "帮我看看", "能不能", "可不可以", "有没有",
            "在哪里", "请问", "想问", "麻烦", "帮我", "一下",
            "怎么办", "怎么", "怎样", "怎么样", "如何", "什么", "哪些", "哪个", "哪里", "在哪",
            "可以", "能否", "你们", "我们", "这个", "那个", "现在",
            "吗", "呢", "吧", "啊", "呀", "嘛", "的", "是", "了", "我", "你"
    );

    /** 检索状态。 */
    public enum Status {
        /** 命中且至少有一条可用片段。 */
        OK,
        /** 检索正常执行但没有命中，表示知识库里没有这条知识。 */
        NO_MATCH,
        /** 问题为空。 */
        EMPTY_QUERY,
        /** 检索实现未接入或调用失败，属于系统能力问题，不是「没有知识」。 */
        UNAVAILABLE
    }

    /**
     * 检索结果。
     *
     * @param status 状态
     * @param query  归一化后的问题
     * @param hits   可用片段，按相关度倒序
     */
    public record Result(Status status, String query, List<KnowledgeHit> hits) {

        /**
         * 是否拿到可回答的内容。
         *
         * @return 有片段返回 true
         */
        public boolean hasHits() {
            return !hits.isEmpty();
        }
    }

    private final KnowledgeSearchPort port;
    private final KnowledgeProperties properties;

    /**
     * @param port       检索端口，可为 null（入口模块未提供实现，此时返回 {@link Status#UNAVAILABLE}）
     * @param properties 知识库配置
     */
    public KnowledgeService(KnowledgeSearchPort port, KnowledgeProperties properties) {
        this.port = port;
        this.properties = properties == null ? new KnowledgeProperties() : properties;
    }

    /**
     * 检索知识片段。
     *
     * @param rawQuery 用户问题或模型构造的检索词
     * @param limit    期望片段数，可为 null；服务端按 {@code max-hits} 封顶
     * @return 检索结果，永不返回 null
     */
    public Result search(String rawQuery, Integer limit) {
        String query = normalizeQuery(rawQuery);
        if (query.isEmpty()) {
            return new Result(Status.EMPTY_QUERY, "", List.of());
        }
        if (port == null) {
            return new Result(Status.UNAVAILABLE, query, List.of());
        }
        int size = clamp(limit);
        List<KnowledgeHit> raw;
        try {
            raw = port.search(query, size);
        } catch (RuntimeException e) {
            // 检索故障不抛给上层：这属于能力降级，模型应提示稍后再试，而不是宣称「没有相关政策」
            log.warn("知识检索失败，按不可用降级：query={} error={}", query, e.getMessage());
            return new Result(Status.UNAVAILABLE, query, List.of());
        }
        if (raw == null || raw.isEmpty()) {
            return new Result(Status.NO_MATCH, query, List.of());
        }
        List<KnowledgeHit> hits = tidy(raw, size);
        return new Result(hits.isEmpty() ? Status.NO_MATCH : Status.OK, query, hits);
    }

    /**
     * 清洗与裁剪命中结果。
     *
     * @param raw  原始命中
     * @param size 目标条数
     * @return 可直接下发的结果
     */
    private List<KnowledgeHit> tidy(List<KnowledgeHit> raw, int size) {
        Set<String> seenSource = new LinkedHashSet<>();
        List<KnowledgeHit> hits = new ArrayList<>(size);
        for (KnowledgeHit hit : raw) {
            if (hit == null || hit.sourceId() == null || hit.sourceId().isBlank()) {
                continue;
            }
            if (hit.score() < properties.getMinScore()) {
                continue;
            }
            if (!seenSource.add(hit.sourceId())) {
                // 同一篇文档的多个片段对模型没有增量信息，保留最高分的那条
                continue;
            }
            String snippet = KnowledgeGuard.sanitize(hit.snippet());
            if (snippet.isEmpty()) {
                // 整段都被判定为指令性内容：宁可少一条片段，也不把可疑文本喂给模型
                log.warn("知识片段清洗后为空，已丢弃 sourceId={}", hit.sourceId());
                continue;
            }
            hits.add(new KnowledgeHit(hit.sourceId(), hit.title(), hit.category(), hit.version(),
                    hit.updatedAt(), truncate(snippet, properties.getMaxSnippetChars()), hit.score()));
            if (hits.size() >= size) {
                break;
            }
        }
        return hits;
    }

    /**
     * 归一化查询串：去零宽字符、压缩空白、剔除疑问词与口头语、按 {@code max-query-chars} 截断。
     * <p>
     * 为什么要剔除疑问词：「在哪里查看我的订单」被 ik 切成
     * {@code 在哪 / 哪里 / 查看 / 看我 / 订单}，其中三个词元在任何文档里都不存在，
     * 但它们会计入 {@code minimum_should_match} 的分母，把「查看 + 订单」这种完全正确的匹配
     * 压到阈值以下。剔掉「哪里 / 怎么 / 可以 / 吗」这类词，再交给 ES，
     * 覆盖率阈值才真正反映「业务词命中了几成」。
     * <p>
     * 只做「删除噪声词」，不做同义词替换：改写语义是模型层的事，
     * 检索层擅自改写会让评测结果无法解释。
     *
     * @param rawQuery 原始查询
     * @return 归一化结果，空串表示无有效查询
     */
    private String normalizeQuery(String rawQuery) {
        if (rawQuery == null) {
            return "";
        }
        String cleaned = rawQuery.replaceAll("[\\u200B-\\u200F\\uFEFF]", "")
                .replaceAll("\\s+", " ")
                .trim();
        for (String noise : NOISE_PHRASES) {
            cleaned = cleaned.replace(noise, " ");
        }
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            // 全是噪声词（例如只问了「怎么办」）时保留原句，让检索层自己去判断，而不是构造空查询
            cleaned = rawQuery.replaceAll("\\s+", " ").trim();
        }
        int max = Math.max(1, properties.getMaxQueryChars());
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    /**
     * 限制返回条数。
     *
     * @param limit 调用方期望值，可为 null
     * @return 生效条数，范围 1..max-hits
     */
    private int clamp(Integer limit) {
        int max = Math.max(1, properties.getMaxHits());
        if (limit == null) {
            return max;
        }
        return Math.min(Math.max(limit, 1), max);
    }

    /**
     * 按句截断，避免把句子切成半句。
     *
     * @param text 文本
     * @param max  上限字符数
     * @return 截断后的文本
     */
    private static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        // 从 max-1 往前找句末标点：命中位置 + 1 仍不超过上限
        int cut = text.lastIndexOf('。', max - 1);
        if (cut > 0) {
            return text.substring(0, cut + 1);
        }
        // 整段没有句末标点（表格、列表、超长单句）时只能硬截断
        return text.substring(0, max);
    }
}
