package com.zeroai.ai.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识文档切块器。
 * <p>
 * <b>为什么向量检索必须先分块</b>：把一整篇政策算成一个向量，这个向量表示的是
 * 「这篇文档大致讲什么」，而用户问的通常是其中一个细节。一篇同时讲「退款条件」
 * 「退款到账时间」「退款金额计算」的文档，整篇向量与这三件事的相似度都很平庸；
 * 切成三块之后，每块向量对准一件事，召回精度才上得来。这不是优化项，是前提。
 * <p>
 * <b>为什么按 Markdown 标题 + 段落切，而不是定长滑窗</b>：定长窗口会把「退款到账
 * 时间为 1-3 个工作日」切成「退款到账时间为 1-3」和「个工作日」，两块都不完整，
 * 向量和片段都会失真。政策文档本身是按小节组织的，小节边界就是天然的语义边界。
 * 只有在单个段落超过 {@code chunk-size} 时才退化为按句切分，且保证不切在句子中间。
 * <p>
 * 纯函数，不读配置、不依赖 Spring，便于单测直接用字符串断言切块结果。
 */
public final class KnowledgeChunker {

    private KnowledgeChunker() {
    }

    /**
     * 一个知识块。
     *
     * @param sourceId  所属文档
     * @param title     文档标题
     * @param category  分类
     * @param version   版本
     * @param updatedAt 更新时间
     * @param section   所属小节标题，可为空（文档开头没有标题的部分）
     * @param text      块文本（已按需拼接标题前缀）
     */
    public record Chunk(String sourceId,
                        String title,
                        String category,
                        int version,
                        String updatedAt,
                        String section,
                        String text) {

        /**
         * 转成检索命中。
         *
         * @param score 相似度
         * @return 命中对象
         */
        public KnowledgeHit toHit(double score) {
            return new KnowledgeHit(sourceId, title, category, version, updatedAt, text, score);
        }
    }

    /**
     * 切分一篇文档。
     *
     * @param document     文档
     * @param chunkSize    目标块长度（字符）
     * @param minChunkChars 最小块长度，尾块过短时并入上一块
     * @param prefixTitle  是否在块文本前拼接「文档标题 / 小节」上下文
     * @return 块列表，按文档顺序；空文档返回空列表
     */
    public static List<Chunk> split(KnowledgeDocument document, int chunkSize, int minChunkChars, boolean prefixTitle) {
        List<Chunk> chunks = new ArrayList<>();
        if (document == null || document.content() == null || document.content().isBlank()) {
            return chunks;
        }
        int size = Math.max(80, chunkSize);
        int minSize = Math.max(0, Math.min(minChunkChars, size / 2));

        Section current = new Section("", new StringBuilder());
        for (String rawLine : document.content().replace("\r\n", "\n").split("\n")) {
            String line = rawLine.strip();
            if (line.startsWith("#")) {
                // 标题行开启新小节：先把上一节剩余内容收成块，再换小节名
                flush(document, chunks, current, size, minSize, prefixTitle);
                current = new Section(cleanHeading(line), new StringBuilder());
                continue;
            }
            if (line.isEmpty()) {
                // 空行是段落边界，但只是「可能可以断」的信号，不立刻 flush：
                // 小节内多个短段落合并成一块，信息密度更高
                continue;
            }
            appendParagraph(document, chunks, current, line, size, minSize, prefixTitle);
        }
        flush(document, chunks, current, size, minSize, prefixTitle);
        return chunks;
    }

    /**
     * 追加一个段落；放不下时先 flush 再开新块。
     *
     * @param document    文档
     * @param chunks      结果容器
     * @param current     当前小节
     * @param paragraph   段落文本
     * @param size        块长度
     * @param minSize     最小块长度
     * @param prefixTitle 是否拼标题前缀
     */
    private static void appendParagraph(KnowledgeDocument document, List<Chunk> chunks, Section current,
                                       String paragraph, int size, int minSize, boolean prefixTitle) {
        if (paragraph.length() > size) {
            // 超长段落（表格化的长条款）按句切，避免一块装不下
            for (String sentence : splitSentences(paragraph, size)) {
                if (current.body.length() > 0 && current.body.length() + sentence.length() + 1 > size) {
                    flush(document, chunks, current, size, minSize, prefixTitle);
                }
                if (current.body.length() > 0) {
                    current.body.append('\n');
                }
                current.body.append(sentence);
            }
            return;
        }
        if (current.body.length() > 0 && current.body.length() + paragraph.length() + 1 > size) {
            flush(document, chunks, current, size, minSize, prefixTitle);
        }
        if (current.body.length() > 0) {
            current.body.append('\n');
        }
        current.body.append(paragraph);
    }

    /**
     * 把当前小节累积的内容收成一个块。
     *
     * @param document    文档
     * @param chunks      结果容器
     * @param current     当前小节，内容被清空
     * @param size        块长度
     * @param minSize     最小块长度
     * @param prefixTitle 是否拼标题前缀
     */
    private static void flush(KnowledgeDocument document, List<Chunk> chunks, Section current,
                              int size, int minSize, boolean prefixTitle) {
        String body = current.body.toString().strip();
        current.body.setLength(0);
        if (body.isEmpty()) {
            return;
        }
        if (body.length() < minSize && !chunks.isEmpty()
                && chunks.get(chunks.size() - 1).text().length() + body.length() <= size * 3 / 2) {
            // 尾块太短：并入同一文档的上一块，避免产出「七、其他」这种零信息块
            int lastIndex = chunks.size() - 1;
            Chunk last = chunks.get(lastIndex);
            chunks.set(lastIndex, new Chunk(last.sourceId(), last.title(), last.category(), last.version(),
                    last.updatedAt(), last.section(), last.text() + "\n" + body));
            return;
        }
        chunks.add(new Chunk(document.sourceId(), document.title(), document.category(), document.version(),
                document.updatedAt(), current.section, withPrefix(document, current.section, body, prefixTitle)));
    }

    /**
     * 拼上下文前缀。
     * <p>
     * 「退款与售后政策 / 退款到账时间：」这类前缀让向量带上主题，
     * 对「多久到账」这种只有四个字的短问句提升明显。
     *
     * @param document    文档
     * @param section     小节
     * @param body        正文
     * @param prefixTitle 是否启用
     * @return 块文本
     */
    private static String withPrefix(KnowledgeDocument document, String section, String body, boolean prefixTitle) {
        if (!prefixTitle) {
            return body;
        }
        return prefixOf(document, section) + body;
    }

    /**
     * 生成前缀。
     *
     * @param document 文档
     * @param section  小节
     * @return 前缀字符串
     */
    private static String prefixOf(KnowledgeDocument document, String section) {
        String title = document.title() == null ? "" : document.title().trim();
        String sec = section == null ? "" : section.trim();
        if (sec.isEmpty()) {
            return title.isEmpty() ? "" : "【" + title + "】";
        }
        return title.isEmpty() ? "【" + sec + "】" : "【" + title + " · " + sec + "】";
    }

    /**
     * 按句切分超长段落。
     *
     * @param paragraph 段落
     * @param size      单块上限
     * @return 句子列表
     */
    private static List<String> splitSentences(String paragraph, int size) {
        List<String> sentences = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (char ch : paragraph.toCharArray()) {
            buffer.append(ch);
            boolean boundary = ch == '。' || ch == '；' || ch == '！' || ch == '？' || ch == ';';
            if (boundary && buffer.length() >= size / 2) {
                sentences.add(buffer.toString().strip());
                buffer.setLength(0);
            } else if (buffer.length() >= size) {
                // 整段没有句末标点（列表、数字串）：只能硬切，但仍是最后手段
                sentences.add(buffer.toString().strip());
                buffer.setLength(0);
            }
        }
        if (!buffer.toString().isBlank()) {
            sentences.add(buffer.toString().strip());
        }
        return sentences;
    }

    /**
     * 去掉 Markdown 标题的井号与首尾空白。
     *
     * @param line 标题行
     * @return 标题文字
     */
    private static String cleanHeading(String line) {
        String value = line;
        while (value.startsWith("#")) {
            value = value.substring(1);
        }
        return value.strip();
    }

    /**
     * 当前累积的小节内容。
     */
    private static final class Section {

        private final String section;
        private final StringBuilder body;

        private Section(String section, StringBuilder body) {
            this.section = section;
            this.body = body;
        }
    }
}
