package com.zeroai.ai.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识文档加载与解析。
 * <p>
 * 知识源是 classpath 下的 Markdown 文件（{@code knowledge/*.md}），每篇带 YAML frontmatter：
 * <pre>
 * ---
 * sourceId: kb-after-sale-refund
 * title: 退款与售后政策
 * category: after-sale
 * version: 1
 * updatedAt: 2026-10-27
 * visibility: public
 * tags: 退款,售后
 * ---
 * 正文……
 * </pre>
 * <p>
 * 为什么用「代码即知识源」而不是后台录入表：政策随代码一起评审、一起发布、一起回滚，
 * 改一句话就是一次可 review 的 diff；不需要额外建表、建后台，也不会出现
 * 「代码已上线但知识库还是旧口径」这种最常见的知识库腐化。
 * <p>
 * 解析是<b>纯函数</b>：不连 ES、不读配置，便于单测直接喂字符串断言。
 * 不合格的文档不会抛异常中断加载，而是记入 {@link LoadResult#rejected()}：
 * 一篇写坏的文档只会少一条知识，不该让整个应用起不来。
 */
public final class KnowledgeCatalog {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCatalog.class);

    /** frontmatter 分隔行。 */
    private static final String FRONTMATTER_DELIMITER = "---";

    /** 必需字段：缺任意一项都视为文档不完整，直接拒收。 */
    private static final List<String> REQUIRED_FIELDS = List.of("sourceId", "title", "updatedAt");

    private KnowledgeCatalog() {
    }

    /**
     * 加载结果。
     *
     * @param documents 通过校验的文档，按 sourceId 排序
     * @param rejected  被拒收的文档及原因
     */
    public record LoadResult(List<KnowledgeDocument> documents, List<Rejected> rejected) {

        /**
         * 只保留可公开检索的文档。
         *
         * @return 公开文档列表
         */
        public List<KnowledgeDocument> publicDocuments() {
            return documents.stream().filter(KnowledgeDocument::isPublic).toList();
        }
    }

    /**
     * 被拒收的文档。
     *
     * @param origin 文件位置
     * @param reason 拒收原因
     */
    public record Rejected(String origin, String reason) {
    }

    /**
     * 从 classpath 目录加载全部知识文档。
     *
     * @param location classpath 目录，例如 {@code knowledge}
     * @return 加载结果；目录不存在时返回空结果而不是抛异常
     */
    public static LoadResult load(String location) {
        List<KnowledgeDocument> documents = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        Map<String, String> seen = new HashMap<>();

        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + trimSlashes(location) + "/*.md");
        } catch (IOException e) {
            log.warn("知识目录扫描失败，按空知识库处理：location={} error={}", location, e.getMessage());
            return new LoadResult(List.of(), List.of(new Rejected(location, "目录扫描失败：" + e.getMessage())));
        }

        for (Resource resource : resources) {
            String origin = resource.getFilename() == null ? resource.getDescription() : resource.getFilename();
            try (InputStream in = resource.getInputStream()) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                parse(text, origin, documents, rejected, seen);
            } catch (IOException e) {
                rejected.add(new Rejected(origin, "读取失败：" + e.getMessage()));
            }
        }
        documents.sort((a, b) -> a.sourceId().compareTo(b.sourceId()));
        if (!rejected.isEmpty()) {
            log.warn("知识文档部分未通过校验，已跳过：{}", rejected);
        }
        log.info("知识文档加载完成 documents={} public={} rejected={} location={}",
                documents.size(), documents.stream().filter(KnowledgeDocument::isPublic).count(),
                rejected.size(), location);
        return new LoadResult(List.copyOf(documents), List.copyOf(rejected));
    }

    /**
     * 解析单篇文档，通过校验后追加到 {@code documents}。
     * <p>
     * 包级可见，供单测直接断言解析细节，不必构造 classpath 资源。
     *
     * @param text      文档全文（含 frontmatter）
     * @param origin    文件来源，仅用于日志
     * @param documents 通过校验的收集容器
     * @param rejected  拒收收集容器
     * @param seen      sourceId 去重表，value 为首次出现的来源
     */
    static void parse(String text, String origin,
                      List<KnowledgeDocument> documents, List<Rejected> rejected, Map<String, String> seen) {
        if (text == null || text.isBlank()) {
            rejected.add(new Rejected(origin, "内容为空"));
            return;
        }
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        if (!normalized.startsWith(FRONTMATTER_DELIMITER)) {
            rejected.add(new Rejected(origin, "缺少 frontmatter"));
            return;
        }
        int end = normalized.indexOf("\n" + FRONTMATTER_DELIMITER, FRONTMATTER_DELIMITER.length());
        if (end < 0) {
            rejected.add(new Rejected(origin, "frontmatter 未闭合"));
            return;
        }
        Map<String, String> meta = parseFrontmatter(
                normalized.substring(FRONTMATTER_DELIMITER.length(), end));
        String content = normalized.substring(end + FRONTMATTER_DELIMITER.length() + 1).trim();

        for (String field : REQUIRED_FIELDS) {
            if (isBlank(meta.get(field))) {
                rejected.add(new Rejected(origin, "缺少必需字段 " + field));
                return;
            }
        }
        if (content.isBlank()) {
            rejected.add(new Rejected(origin, "正文为空"));
            return;
        }
        // 入库前的注入检查：知识文档是构建期产物，出现元指令说明来源被篡改，整篇拒收
        var suspicious = KnowledgeGuard.inspect(content);
        if (suspicious.isPresent()) {
            rejected.add(new Rejected(origin, "命中注入特征【" + suspicious.get() + "】"));
            log.error("知识文档包含指令性内容，已拒绝入库：origin={} sourceId={} rule={}",
                    origin, meta.get("sourceId"), suspicious.get());
            return;
        }

        String sourceId = meta.get("sourceId").trim();
        if (seen.containsKey(sourceId)) {
            rejected.add(new Rejected(origin, "sourceId 重复，首次出现于 " + seen.get(sourceId)));
            return;
        }
        seen.put(sourceId, origin);

        documents.add(new KnowledgeDocument(
                sourceId,
                meta.get("title").trim(),
                valueOrDefault(meta.get("category"), "general"),
                parseInt(meta.get("version"), 1),
                meta.get("updatedAt").trim(),
                KnowledgeVisibility.from(meta.get("visibility")),
                splitTags(meta.get("tags")),
                content).normalized());
    }

    /**
     * 解析 frontmatter 的 {@code key: value} 行。
     * <p>
     * 只支持本项目用到的扁平日志式写法，不引入 YAML 解析库：知识文档结构固定，
     * 引入完整 YAML 只会带来「多行字符串、锚点、类型推断」这些我们并不需要的行为差异。
     *
     * @param block frontmatter 正文（不含分隔行）
     * @return 字段表，保持书写顺序
     */
    private static Map<String, String> parseFrontmatter(String block) {
        Map<String, String> meta = new LinkedHashMap<>();
        for (String rawLine : block.split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            meta.put(key, value);
        }
        return meta;
    }

    /**
     * 拆分标签，支持中英文逗号、顿号与竖线。
     *
     * @param raw 原始值
     * @return 标签列表
     */
    private static List<String> splitTags(String raw) {
        if (isBlank(raw)) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String tag : raw.split("[,，、|]")) {
            String trimmed = tag.strip();
            if (!trimmed.isEmpty()) {
                tags.add(trimmed);
            }
        }
        return tags;
    }

    /**
     * 解析整数，非法值回落默认值但不阻断加载：版本号写错不影响检索。
     *
     * @param raw        原始值
     * @param defaultVal 默认值
     * @return 解析结果
     */
    private static int parseInt(String raw, int defaultVal) {
        if (isBlank(raw)) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    /**
     * 取字段值，空值用默认值替代。
     *
     * @param raw        原始值
     * @param defaultVal 默认值
     * @return 非空值
     */
    private static String valueOrDefault(String raw, String defaultVal) {
        return isBlank(raw) ? defaultVal : raw.trim();
    }

    /**
     * 判空。
     *
     * @param value 原始值
     * @return 为 null 或空白返回 true
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 去掉目录两端的斜杠，避免拼出 {@code classpath*:/knowledge//*.md}。
     *
     * @param location 目录配置
     * @return 规范化目录
     */
    private static String trimSlashes(String location) {
        String value = location == null || location.isBlank() ? "knowledge" : location.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
