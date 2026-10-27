package com.zeroai.ai.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内向量索引测试。
 * <p>
 * 用「关键词计数」假 embedder 代替真模型：这里要验证的是索引的排序、去重、
 * 阈值与失败语义，不是模型效果。用真模型会让测试依赖网络且结果不可复现。
 */
class InMemoryVectorIndexTest {

    /** 四个维度分别对应四个业务词，出现次数即权重。 */
    private static final String[] KEYWORDS = {"退款", "运费", "优惠券", "发票"};

    /**
     * 确定性的假向量化：同一段文本永远得到同一向量。
     */
    private static final class KeywordEmbedder implements KnowledgeEmbedder {

        private final float boost;
        private RuntimeException failure;

        private KeywordEmbedder() {
            this(0.01f);
        }

        private KeywordEmbedder(float boost) {
            this.boost = boost;
        }

        @Override
        public float[] embed(String text) {
            if (failure != null) {
                throw failure;
            }
            float[] vector = new float[KEYWORDS.length];
            for (int i = 0; i < KEYWORDS.length; i++) {
                vector[i] = count(text, KEYWORDS[i]) + boost;
            }
            return vector;
        }

        @Override
        public int dimension() {
            return KEYWORDS.length;
        }

        @Override
        public String model() {
            return "keyword-fake";
        }
    }

    private static int count(String text, String keyword) {
        int total = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(keyword, from);
            if (at < 0) {
                return total;
            }
            total++;
            from = at + keyword.length();
        }
    }

    private static KnowledgeChunker.Chunk chunk(String sourceId, String text) {
        return new KnowledgeChunker.Chunk(sourceId, "标题-" + sourceId, "general", 1, "2026-10-27", "小节", text);
    }

    private static List<KnowledgeChunker.Chunk> sampleChunks() {
        List<KnowledgeChunker.Chunk> chunks = new ArrayList<>();
        chunks.add(chunk("kb-refund", "退款申请与退款到账时间说明，退款审核通过后原路退回。"));
        chunks.add(chunk("kb-freight", "运费规则：满 99 元包邮，不满 99 元收运费 8 元。"));
        chunks.add(chunk("kb-coupon", "优惠券使用规则与门槛说明。"));
        return chunks;
    }

    @Test
    @DisplayName("按余弦相似度降序返回，相关块排第一")
    void shouldRankByCosine() {
        InMemoryVectorIndex index = new InMemoryVectorIndex();
        KeywordEmbedder embedder = new KeywordEmbedder();
        index.rebuild(sampleChunks(), embedder);

        List<KnowledgeHit> hits = index.search(embedder.embed("退款到账要多久"), 3, 0.5);

        assertFalse(hits.isEmpty());
        assertEquals("kb-refund", hits.get(0).sourceId());
        assertTrue(hits.get(0).score() > 0.9, "同主题应接近 1，实际=" + hits.get(0).score());
    }

    @Test
    @DisplayName("同一文档的多块只保留最高分一块，不挤占别人的名额")
    void shouldKeepOneChunkPerSource() {
        InMemoryVectorIndex index = new InMemoryVectorIndex();
        KeywordEmbedder embedder = new KeywordEmbedder();
        index.rebuild(List.of(
                chunk("kb-refund", "退款退款退款，退款规则。"),
                chunk("kb-refund", "退款补充说明。"),
                chunk("kb-freight", "运费说明。")), embedder);

        List<KnowledgeHit> hits = index.search(embedder.embed("退款"), 3, 0.0);

        assertEquals(2, hits.size(), "同一 sourceId 只能出现一次");
        assertEquals("kb-refund", hits.get(0).sourceId());
        assertEquals("kb-freight", hits.get(1).sourceId());
        assertEquals(2, index.countBySource("kb-refund"));
    }

    @Test
    @DisplayName("低于阈值的块被丢弃，宁可不返回也不给不相关内容")
    void shouldFilterByMinScore() {
        InMemoryVectorIndex index = new InMemoryVectorIndex();
        KeywordEmbedder embedder = new KeywordEmbedder();
        index.rebuild(sampleChunks(), embedder);

        List<KnowledgeHit> hits = index.search(embedder.embed("发票怎么开"), 3, 0.9);

        assertTrue(hits.isEmpty(), "问发票时不该硬凑退款/运费/优惠券");
    }

    @Test
    @DisplayName("查询向量维度与索引不符时跳过而不是算出假分数")
    void shouldSkipDimensionMismatch() {
        InMemoryVectorIndex index = new InMemoryVectorIndex();
        index.rebuild(sampleChunks(), new KeywordEmbedder());

        List<KnowledgeHit> hits = index.search(new float[]{1f, 0f}, 3, 0.1);

        assertTrue(hits.isEmpty(), "维度不一致说明模型换了却没重建索引，此时任何分数都不可信");
    }

    @Test
    @DisplayName("重建失败时旧索引保持可用")
    void shouldKeepOldIndexWhenRebuildFails() {
        InMemoryVectorIndex index = new InMemoryVectorIndex();
        KeywordEmbedder embedder = new KeywordEmbedder();
        index.rebuild(sampleChunks(), embedder);

        KeywordEmbedder failing = new KeywordEmbedder();
        failing.failure = new KnowledgeEmbeddingException("服务挂了");

        assertThrows(KnowledgeEmbeddingException.class,
                () -> index.rebuild(List.of(chunk("kb-new", "新文档")), failing));
        assertEquals(3, index.size(), "失败不能把已有索引清空");
        assertFalse(index.search(embedder.embed("退款"), 1, 0.5).isEmpty());
    }

    @Test
    @DisplayName("空索引与空向量返回空列表，不抛异常")
    void shouldHandleEmptyInputs() {
        InMemoryVectorIndex index = new InMemoryVectorIndex();

        assertTrue(index.search(new float[]{1f, 0f, 0f, 0f}, 3, 0.1).isEmpty());
        assertEquals(0, index.rebuild(List.of(), new KeywordEmbedder()));
        index.rebuild(sampleChunks(), new KeywordEmbedder());
        assertTrue(index.search(null, 3, 0.1).isEmpty());
        assertTrue(index.search(new float[0], 3, 0.1).isEmpty());
    }

    @Test
    @DisplayName("归一化后点积等于余弦，零向量不会被算成 NaN")
    void shouldNormalizeSafely() {
        float[] normalized = InMemoryVectorIndex.normalize(new float[]{3f, 4f});
        assertEquals(0.6, normalized[0], 1e-6);
        assertEquals(0.8, normalized[1], 1e-6);

        float[] zero = InMemoryVectorIndex.normalize(new float[]{0f, 0f});
        assertEquals(0f, zero[0], 1e-6);
        assertEquals(0f, zero[1], 1e-6);
    }
}
