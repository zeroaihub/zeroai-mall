package com.zeroai.ai.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量检索实现测试。
 * <p>
 * 用 classpath 里真实的 6 篇知识文档 + 关键词假 embedder 跑通「加载 → 切块 →
 * 向量化 → 检索」整条链路。真模型会把测试变成网络依赖，而这里要验证的是
 * 链路与状态语义（未就绪 / 未配置 / 失败），不是语义效果。
 */
class VectorKnowledgeStoreTest {

    /** 四个维度分别对应四个业务词。 */
    private static final String[] KEYWORDS = {"退款", "运费", "优惠券", "发票"};

    /**
     * 关键词计数式假向量化：同一文本永远同向量，便于断言。
     */
    private static final class KeywordEmbedder implements KnowledgeEmbedder {

        private RuntimeException failure;
        private int calls;

        @Override
        public float[] embed(String text) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            float[] vector = new float[KEYWORDS.length];
            for (int i = 0; i < KEYWORDS.length; i++) {
                vector[i] = count(text, KEYWORDS[i]) + 0.01f;
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

    private static KnowledgeProperties props(KnowledgeMode mode) {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.setMode(mode.code());
        return properties;
    }

    @Test
    @DisplayName("未配置 embedding：检索抛不可用，而不是空结果")
    void shouldFailWhenEmbedderMissing() {
        VectorKnowledgeStore store = new VectorKnowledgeStore(null, props(KnowledgeMode.VECTOR));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> store.search("退款", 3));

        assertTrue(error.getMessage().contains("embedding 未配置"));
    }

    @Test
    @DisplayName("索引未构建时检索抛不可用：不能说成「没有这条政策」")
    void shouldFailWhenIndexNotReady() {
        VectorKnowledgeStore store = new VectorKnowledgeStore(new KeywordEmbedder(), props(KnowledgeMode.VECTOR));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> store.search("退款", 3));

        assertTrue(error.getMessage().contains("向量索引未就绪"));
    }

    @Test
    @DisplayName("sync 能加载真实知识文档并切块向量化")
    void shouldBuildIndexFromRealKnowledgeBase() {
        VectorKnowledgeStore store = new VectorKnowledgeStore(new KeywordEmbedder(), props(KnowledgeMode.VECTOR));

        VectorKnowledgeStore.Report report = store.sync();

        assertEquals(6, report.documents(), "知识库固定 6 篇，改动这里说明知识源变了");
        assertTrue(report.chunks() > report.documents(), "平均每篇至少切成一块以上");
        assertEquals(0, report.rejected());
        assertTrue(store.size() > 0);
    }

    @Test
    @DisplayName("sync 后能召回，且片段带来源与更新时间")
    void shouldSearchAfterSync() {
        VectorKnowledgeStore store = new VectorKnowledgeStore(new KeywordEmbedder(), props(KnowledgeMode.VECTOR));
        store.sync();

        List<KnowledgeHit> hits = store.search("退款到账时间", 3);

        assertFalse(hits.isEmpty(), "含「退款」的块相似度接近 1，必须能召回");
        // 不断言具体命中哪一篇：假 embedder 只按关键词计数，同主题文档分数几乎相同。
        // 「哪一篇更对」是评测脚本（scripts/agent/knowledge）用真模型验证的事，
        // 这里只保证链路产出的是知识库里真实存在的文档、且引用要素齐全。
        assertTrue(hits.stream().allMatch(hit -> hit.sourceId().startsWith("kb-")),
                "命中必须来自知识库，实际=" + hits.stream().map(KnowledgeHit::sourceId).toList());
        assertFalse(hits.get(0).snippet().isBlank());
        assertFalse(hits.get(0).title().isBlank());
        assertTrue(hits.get(0).updatedAt() != null, "引用需要有效期，updatedAt 不能丢");
        assertTrue(hits.get(0).version() >= 1);
    }

    @Test
    @DisplayName("bm25 模式下启动不构建向量索引，避免无谓的 embedding 调用")
    void shouldSkipBuildInBm25Mode() {
        KeywordEmbedder embedder = new KeywordEmbedder();
        VectorKnowledgeStore store = new VectorKnowledgeStore(embedder, props(KnowledgeMode.BM25));

        store.run(new DefaultApplicationArguments(new String[0]));

        assertEquals(0, store.size());
        assertEquals(0, embedder.calls, "纯 BM25 模式不该产生任何 embedding 调用");
    }

    @Test
    @DisplayName("向量模式下启动自动构建索引")
    void shouldBuildOnStartupInVectorMode() {
        VectorKnowledgeStore store = new VectorKnowledgeStore(new KeywordEmbedder(), props(KnowledgeMode.VECTOR));

        assertDoesNotThrow(() -> store.run(new DefaultApplicationArguments(new String[0])));
        assertTrue(store.size() > 0);
    }

    @Test
    @DisplayName("向量化失败：启动不阻断，但索引保持为空，检索报不可用")
    void shouldSurviveEmbeddingFailure() {
        KeywordEmbedder embedder = new KeywordEmbedder();
        embedder.failure = new KnowledgeEmbeddingException("embedding 服务 502");
        VectorKnowledgeStore store = new VectorKnowledgeStore(embedder, props(KnowledgeMode.VECTOR));

        // 启动钩子必须吞掉异常：知识库是可选能力，embedding 抖动不该让商城起不来
        assertDoesNotThrow(() -> store.run(new DefaultApplicationArguments(new String[0])));
        assertEquals(0, store.size());
        assertThrows(IllegalStateException.class, () -> store.search("退款", 3));
    }

    @Test
    @DisplayName("sync 失败时向上抛，便于运维在启动日志里看到根因")
    void shouldPropagateSyncFailure() {
        KeywordEmbedder embedder = new KeywordEmbedder();
        embedder.failure = new KnowledgeEmbeddingException("embedding 服务 502");
        VectorKnowledgeStore store = new VectorKnowledgeStore(embedder, props(KnowledgeMode.VECTOR));

        assertThrows(KnowledgeEmbeddingException.class, store::sync);
    }

    @Test
    @DisplayName("block 数不超过「文档数 × 平均块数」的合理上限，说明切块没有失控")
    void chunkCountShouldStayReasonable() {
        VectorKnowledgeStore store = new VectorKnowledgeStore(new KeywordEmbedder(), props(KnowledgeMode.VECTOR));

        VectorKnowledgeStore.Report report = store.sync();

        // 6 篇文档、chunk-size 300：正常在 20~40 块之间；超过 100 说明切块逻辑退化成逐行切
        assertTrue(report.chunks() < 100, "模块数异常，实际=" + report.chunks());
        assertTrue(report.chunks() >= 6, "至少每篇一块，实际=" + report.chunks());
    }
}
