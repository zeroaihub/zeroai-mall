package com.zeroai.ai.knowledge;

import java.util.List;

/**
 * 查询串归一化。
 * <p>
 * 分成两种强度，因为<b>两种检索对查询串的要求是相反的</b>：
 * <ul>
 *   <li>{@link #clean}：只做字符级清理与截断，<b>不动语义</b>。向量检索要用这个——
 *       embedding 模型理解的是完整句子，「在哪里查看退款进度」去掉「在哪里」后
 *       语义反而变弱；</li>
 *   <li>{@link #stripNoise}：在 clean 基础上剔除疑问词与口头语。倒排索引要用这个——
 *       ik 会把「在哪里」切成 {@code 在哪 / 哪里}，这两个词元在任何政策文档里都不存在，
 *       却会计入 {@code minimum_should_match} 的分母，把「查看 + 订单 + 退款」
 *       这种完全正确的匹配压到阈值以下。</li>
 * </ul>
 * 把两者分开是刻意的：这正是「同一句问话，字面检索需要洗、语义检索不需要洗」
 * 这个事实在代码里的落点。如果只有一种归一化，切换检索模式时必然有一边吃亏。
 */
public final class KnowledgeQueryNormalizer {

    /**
     * 倒排索引检索前剔除的噪声词。按长度倒序匹配，避免「在哪里」被「哪里」提前截断。
     * <p>
     * 只收疑问词、口头语与第一/第二人称，<b>绝不收业务词</b>：
     * 把「退款」「运费」停掉等于自废召回。
     */
    private static final List<String> NOISE_PHRASES = List.of(
            "想问一下", "请问一下", "帮我看下", "帮我看看", "能不能", "可不可以", "有没有",
            "在哪里", "请问", "想问", "麻烦", "帮我", "一下",
            "怎么办", "怎么", "怎样", "怎么样", "如何", "什么", "哪些", "哪个", "哪里", "在哪",
            "可以", "能否", "你们", "我们", "这个", "那个", "现在",
            "吗", "呢", "吧", "啊", "呀", "嘛", "的", "是", "了", "我", "你"
    );

    private KnowledgeQueryNormalizer() {
    }

    /**
     * 字符级清理：去零宽字符与 BOM、压缩空白、按上限截断。
     * <p>
     * 零宽字符不清理会出现「看起来一样但检索不到」的怪事——用户从网页复制的文本
     * 里常夹带这些字符。
     *
     * @param rawQuery 原始查询
     * @param maxChars 最大长度，<=0 表示不限
     * @return 清理后的查询，可能为空串
     */
    public static String clean(String rawQuery, int maxChars) {
        if (rawQuery == null) {
            return "";
        }
        String cleaned = rawQuery.replaceAll("[\\u200B-\\u200F\\uFEFF]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (maxChars > 0 && cleaned.length() > maxChars) {
            return cleaned.substring(0, maxChars);
        }
        return cleaned;
    }

    /**
     * 为倒排索引准备查询串：先 clean，再剔除噪声词。
     * <p>
     * 全被剔空时（例如只问了「怎么办」）回退到 clean 的结果，
     * 让检索层自己去判断，而不是构造一个空查询。
     *
     * @param rawQuery 原始查询
     * @param maxChars 最大长度，<=0 表示不限
     * @return 归一化结果
     */
    public static String stripNoise(String rawQuery, int maxChars) {
        String cleaned = clean(rawQuery, maxChars);
        if (cleaned.isEmpty()) {
            return "";
        }
        String stripped = cleaned;
        for (String noise : NOISE_PHRASES) {
            stripped = stripped.replace(noise, " ");
        }
        stripped = stripped.replaceAll("\\s+", " ").trim();
        if (stripped.isEmpty()) {
            return cleaned;
        }
        return maxChars > 0 && stripped.length() > maxChars ? stripped.substring(0, maxChars) : stripped;
    }
}
