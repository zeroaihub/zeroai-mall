package com.zeroai.ai.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识切块测试。
 * <p>
 * 切块质量直接决定向量检索的上限：块太大，向量表示的是「整篇讲什么」而不是
 * 「这条规则说什么」；块太小，一条规则被切成两半，两块都答不了问题。
 * 这里盯住三件事：按小节切、不切断句子、不产出零信息尾块。
 */
class KnowledgeChunkerTest {

    private static KnowledgeDocument doc(String content) {
        return new KnowledgeDocument("kb-test", "退款与售后政策", "after-sale", 1, "2026-10-27",
                KnowledgeVisibility.PUBLIC, List.of("退款"), content).normalized();
    }

    @Test
    @DisplayName("按 Markdown 小节切块，块上带小节名")
    void shouldSplitBySection() {
        String content = """
                ## 退款申请
                在订单详情页点「申请退款」，填写退款原因即可提交。
                提交后商家会在 24 小时内处理。

                ## 退款到账
                审核通过后原路退回，银行卡 1-3 个工作日到账。
                """;

        List<KnowledgeChunker.Chunk> chunks = KnowledgeChunker.split(doc(content), 300, 20, false);

        assertEquals(2, chunks.size());
        assertEquals("退款申请", chunks.get(0).section());
        assertEquals("退款到账", chunks.get(1).section());
        assertTrue(chunks.get(0).text().contains("申请退款"));
        assertTrue(chunks.get(1).text().contains("1-3 个工作日"));
    }

    @Test
    @DisplayName("同一小节内的多个段落合并成一块，不按空行碎切")
    void shouldMergeParagraphsInSameSection() {
        String content = """
                ## 运费
                满 99 元包邮。
                不满 99 元收 8 元运费。
                偏远地区另加 12 元。
                """;

        List<KnowledgeChunker.Chunk> chunks = KnowledgeChunker.split(doc(content), 300, 20, false);

        assertEquals(1, chunks.size(), "三个短段落应合成一块，而不是三个碎片");
        assertTrue(chunks.get(0).text().contains("偏远地区"));
    }

    @Test
    @DisplayName("超过块长度的长段落按句切，且不切在句子中间")
    void shouldSplitLongParagraphAtSentenceBoundary() {
        StringBuilder content = new StringBuilder("## 退款金额\n");
        for (int i = 0; i < 12; i++) {
            content.append("第").append(i).append("条规则说明退款金额的计算方式与上限。");
        }

        List<KnowledgeChunker.Chunk> chunks = KnowledgeChunker.split(doc(content.toString()), 100, 20, false);

        assertTrue(chunks.size() > 1, "长段落必须被切开");
        for (KnowledgeChunker.Chunk chunk : chunks) {
            assertTrue(chunk.text().length() <= 100, "块长度不得超过上限，实际=" + chunk.text().length());
            assertTrue(chunk.text().endsWith("。"), "块必须结束在句末，实际=" + chunk.text());
        }
    }

    @Test
    @DisplayName("过短的尾块并入上一块，避免产出「七、其他」式零信息块")
    void shouldMergeShortTailChunk() {
        String content = """
                ## 退款申请
                在订单详情页点「申请退款」，填写退款原因后提交，商家会在 24 小时内处理完成。

                ## 其他
                未列明事项以商城公告为准。
                """;

        List<KnowledgeChunker.Chunk> chunks = KnowledgeChunker.split(doc(content), 300, 80, false);

        assertEquals(1, chunks.size(), "短尾块应被吸收");
        assertTrue(chunks.get(0).text().contains("未列明事项"), "合并后内容不能丢");
    }

    @Test
    @DisplayName("开启前缀时，块文本带「标题 · 小节」上下文")
    void shouldPrefixTitleAndSection() {
        String content = "## 退款到账\n审核通过后原路退回。";

        List<KnowledgeChunker.Chunk> chunks = KnowledgeChunker.split(doc(content), 300, 20, true);

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).text().startsWith("【退款与售后政策 · 退款到账】"),
                "前缀让「多久到账」这类短问句也能对上主题，实际=" + chunks.get(0).text());
    }

    @Test
    @DisplayName("空内容与 null 不抛异常，返回空列表")
    void shouldReturnEmptyForBlankContent() {
        assertTrue(KnowledgeChunker.split(doc("   "), 300, 20, true).isEmpty());
        assertTrue(KnowledgeChunker.split(null, 300, 20, true).isEmpty());
    }

    @Test
    @DisplayName("块的元数据与来源文档一致，保证回答能引用到正确文档")
    void chunkShouldCarrySourceMetadata() {
        List<KnowledgeChunker.Chunk> chunks = KnowledgeChunker.split(doc("## 退款\n可以退。"), 300, 20, false);

        KnowledgeChunker.Chunk chunk = chunks.get(0);
        assertEquals("kb-test", chunk.sourceId());
        assertEquals("退款与售后政策", chunk.title());
        assertEquals("after-sale", chunk.category());
        assertEquals(1, chunk.version());
        assertEquals("2026-10-27", chunk.updatedAt());
        assertFalse(chunk.toHit(0.9).snippet().isEmpty());
    }
}
