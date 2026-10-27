package com.zeroai.ai.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库配置。
 * <p>
 * 知识库是<b>可选扩展</b>，可以整体关闭：关闭后不注册任何 Bean，
 * Agent 的工具面里也不会出现 {@code search_knowledge}，行为与未接入知识库时一致。
 * <p>
 * 这里不出现「向量维度」「相似度阈值」这类参数，因为当前实现复用 Elasticsearch 的
 * BM25 检索，没有引入向量库。真需要语义召回时再加，不要提前留空壳配置。
 */
@ConfigurationProperties(prefix = "zeroai.ai.knowledge")
public class KnowledgeProperties {

    /**
     * 知识库开关。关闭后对话里不会出现政策类查询工具。
     */
    private boolean enabled = true;

    /**
     * 知识索引名。商品实时数据仍在 {@code goods} 索引，这里只存规则类文档，两者不混用。
     */
    private String index = "zeroai_ai_kb";

    /**
     * 知识文档的 classpath 位置（jar 内目录）。
     */
    private String location = "knowledge";

    /**
     * 是否在应用启动时同步索引。同步是幂等的：内容未变的文档也会覆盖写入，
     * 成本是几十次 bulk 写入，换来「代码即知识源」的一致语义。
     */
    private boolean syncOnStartup = true;

    /**
     * 单次检索返回的最大片段数。片段越多上下文越贵，且后半段往往是噪声。
     */
    private int maxHits = 3;

    /**
     * 单个片段允许的最大字符数。超出按句截断，避免一篇长文挤占整个上下文。
     */
    private int maxSnippetChars = 600;

    /**
     * 单次检索允许的最大问题长度。超长输入对检索没有帮助，只会放大查询开销。
     */
    private int maxQueryChars = 80;

    /**
     * 检索得分下限，低于该值的片段直接丢弃。
     * <p>
     * BM25 分数没有统一量纲，这个值只能按语料实测调：设太高会把「换个说法问」的正常命中滤掉，
     * 设 0 表示不启用。默认 1.5 来自本项目 29 条评测用例的实测分布：
     * 正确命中普遍在 2.4 以上，纯噪声命中多在 1.7 以下。
     */
    private double minScore = 1.5D;

    /**
     * 查询词覆盖率要求，直接透传给 Elasticsearch 的 {@code minimum_should_match}。
     * <p>
     * 默认 {@code 2<70%} 的含义是：查询切出 2 个以内的词元时必须全部命中；
     * 超过 2 个时要求命中 70%（向下取整）。这是「问句里夹带无关词也能召回」与
     * 「只共用一个泛词就硬凑一篇文档」之间的折中——后者会让模型拿着错文档回答政策问题，
     * 比查不到危险得多。0.7 而不是 0.8：中文问句被 ik 切出的词元数偏多，
     * 80% 会把「退款多久能到账」这类正常问法挡在门外（实测）。
     */
    private String minShouldMatch = "2<70%";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIndex() {
        return index;
    }

    public void setIndex(String index) {
        this.index = index;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public boolean isSyncOnStartup() {
        return syncOnStartup;
    }

    public void setSyncOnStartup(boolean syncOnStartup) {
        this.syncOnStartup = syncOnStartup;
    }

    public int getMaxHits() {
        return maxHits;
    }

    public void setMaxHits(int maxHits) {
        this.maxHits = maxHits;
    }

    public int getMaxSnippetChars() {
        return maxSnippetChars;
    }

    public void setMaxSnippetChars(int maxSnippetChars) {
        this.maxSnippetChars = maxSnippetChars;
    }

    public int getMaxQueryChars() {
        return maxQueryChars;
    }

    public void setMaxQueryChars(int maxQueryChars) {
        this.maxQueryChars = maxQueryChars;
    }

    public double getMinScore() {
        return minScore;
    }

    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }

    public String getMinShouldMatch() {
        return minShouldMatch;
    }

    public void setMinShouldMatch(String minShouldMatch) {
        this.minShouldMatch = minShouldMatch;
    }
}
