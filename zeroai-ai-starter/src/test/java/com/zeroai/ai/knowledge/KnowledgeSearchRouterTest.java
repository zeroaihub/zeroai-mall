package com.zeroai.ai.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索路由测试。
 * <p>
 * 这里验证的是「切换」本身：配 bm25 时绝不能偷偷调向量、配 vector 时也不能回落 ES，
 * 以及混合模式在有一路挂掉时的行为必须可预测——否则线上看到的现象（回答变差）
 * 和日志（一切正常）会对不上，是最难查的一类问题。
 */
class KnowledgeSearchRouterTest {

    private static KnowledgeHit hit(String sourceId) {
        return new KnowledgeHit(sourceId, "标题-" + sourceId, "general", 1, "2026-10-27", "片段-" + sourceId, 1.0);
    }

    /**
     * BM25 假实现。
     */
    private static final class StubBm25 implements Bm25KnowledgePort {

        private final List<KnowledgeHit> hits;
        private RuntimeException failure;
        private int calls;

        private StubBm25(List<KnowledgeHit> hits) {
            this.hits = hits;
        }

        @Override
        public List<KnowledgeHit> search(String query, int limit) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return hits;
        }
    }

    /**
     * 向量假实现。
     */
    private static final class StubVector implements VectorKnowledgePort {

        private final List<KnowledgeHit> hits;
        private RuntimeException failure;
        private int calls;

        private StubVector(List<KnowledgeHit> hits) {
            this.hits = hits;
        }

        @Override
        public List<KnowledgeHit> search(String query, int limit) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return hits;
        }
    }

    private static KnowledgeProperties props(KnowledgeMode mode) {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.setMode(mode.code());
        return properties;
    }

    @Test
    @DisplayName("bm25 模式只走倒排，不产生任何向量调用")
    void shouldRouteToBm25Only() {
        StubBm25 bm25 = new StubBm25(List.of(hit("kb-a")));
        StubVector vector = new StubVector(List.of(hit("kb-vector")));
        KnowledgeSearchRouter router = new KnowledgeSearchRouter(bm25, vector, props(KnowledgeMode.BM25));

        List<KnowledgeHit> hits = router.search("退款", 3);

        assertEquals(1, hits.size());
        assertEquals("kb-a", hits.get(0).sourceId());
        assertEquals(1, bm25.calls);
        assertEquals(0, vector.calls, "bm25 模式不该花 embedding 的钱");
        assertEquals(KnowledgeMode.BM25, router.mode());
    }

    @Test
    @DisplayName("vector 模式只走向量")
    void shouldRouteToVectorOnly() {
        StubBm25 bm25 = new StubBm25(List.of(hit("kb-a")));
        StubVector vector = new StubVector(List.of(hit("kb-vector")));
        KnowledgeSearchRouter router = new KnowledgeSearchRouter(bm25, vector, props(KnowledgeMode.VECTOR));

        List<KnowledgeHit> hits = router.search("钱什么时候退回来", 3);

        assertEquals("kb-vector", hits.get(0).sourceId());
        assertEquals(0, bm25.calls);
        assertEquals(1, vector.calls);
    }

    @Test
    @DisplayName("hybrid 模式：两路都认可的文档排第一，单路命中仍然保留")
    void shouldFuseByRrf() {
        // bm25: A, B   vector: B, C
        StubBm25 bm25 = new StubBm25(List.of(hit("kb-a"), hit("kb-b")));
        StubVector vector = new StubVector(List.of(hit("kb-b"), hit("kb-c")));
        KnowledgeSearchRouter router = new KnowledgeSearchRouter(bm25, vector, props(KnowledgeMode.HYBRID));

        List<KnowledgeHit> hits = router.search("退货钱多久到", 3);

        assertEquals(3, hits.size(), "单路命中不能被融合阈值误杀");
        assertEquals("kb-b", hits.get(0).sourceId(), "两路都命中的必须排第一");
        assertTrue(hits.get(0).score() > hits.get(1).score(), "融合分应拉开差距");
        assertEquals(1, bm25.calls);
        assertEquals(1, vector.calls);
    }

    @Test
    @DisplayName("hybrid 模式下向量分支失败：默认退回纯 BM25，不整体报错")
    void shouldFallbackToBm25WhenVectorFails() {
        StubBm25 bm25 = new StubBm25(List.of(hit("kb-a")));
        StubVector vector = new StubVector(List.of());
        vector.failure = new IllegalStateException("embedding 服务 502");
        KnowledgeSearchRouter router = new KnowledgeSearchRouter(bm25, vector, props(KnowledgeMode.HYBRID));

        List<KnowledgeHit> hits = router.search("退款", 3);

        assertEquals(1, hits.size());
        assertEquals("kb-a", hits.get(0).sourceId());
    }

    @Test
    @DisplayName("hybrid + 关闭回退：向量分支失败时明确报不可用")
    void shouldFailWhenVectorFailsAndFallbackDisabled() {
        KnowledgeProperties properties = props(KnowledgeMode.HYBRID);
        properties.getHybrid().setFallbackToBm25(false);
        StubBm25 bm25 = new StubBm25(List.of(hit("kb-a")));
        StubVector vector = new StubVector(List.of());
        vector.failure = new IllegalStateException("embedding 服务 502");
        KnowledgeSearchRouter router = new KnowledgeSearchRouter(bm25, vector, properties);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> router.search("退款", 3));

        assertTrue(error.getMessage().contains("未开启回退"), "错误信息要说清是哪一路、为什么失败");
    }

    @Test
    @DisplayName("hybrid 模式下 BM25 分支失败：退化为纯向量")
    void shouldDegradeToVectorWhenBm25Fails() {
        StubBm25 bm25 = new StubBm25(List.of());
        bm25.failure = new IllegalStateException("es down");
        StubVector vector = new StubVector(List.of(hit("kb-vector")));
        KnowledgeSearchRouter router = new KnowledgeSearchRouter(bm25, vector, props(KnowledgeMode.HYBRID));

        List<KnowledgeHit> hits = router.search("退款", 3);

        assertEquals("kb-vector", hits.get(0).sourceId());
    }

    @Test
    @DisplayName("hybrid 模式下两路都挂：抛异常，由门面降级为「不可用」")
    void shouldFailWhenBothBranchesFail() {
        StubBm25 bm25 = new StubBm25(List.of());
        bm25.failure = new IllegalStateException("es down");
        StubVector vector = new StubVector(List.of());
        vector.failure = new IllegalStateException("embedding down");
        KnowledgeSearchRouter router = new KnowledgeSearchRouter(bm25, vector, props(KnowledgeMode.HYBRID));

        assertThrows(IllegalStateException.class, () -> router.search("退款", 3));
    }

    @Test
    @DisplayName("实现缺失时给出明确的不可用，而不是空结果")
    void shouldFailWhenPortMissing() {
        KnowledgeSearchRouter bm25Router = new KnowledgeSearchRouter(null, null, props(KnowledgeMode.BM25));
        assertThrows(IllegalStateException.class, () -> bm25Router.search("退款", 3));

        KnowledgeSearchRouter vectorRouter = new KnowledgeSearchRouter(null, null, props(KnowledgeMode.VECTOR));
        assertThrows(IllegalStateException.class, () -> vectorRouter.search("退款", 3));
    }

    @Test
    @DisplayName("hybrid 模式只装配了一路时，退化为那一路可用结果")
    void shouldHandleSingleBranchInHybrid() {
        KnowledgeSearchRouter withVectorOnly = new KnowledgeSearchRouter(
                null, new StubVector(List.of(hit("kb-vector"))), props(KnowledgeMode.HYBRID));
        assertEquals("kb-vector", withVectorOnly.search("退款", 3).get(0).sourceId());

        KnowledgeSearchRouter withBm25Only = new KnowledgeSearchRouter(
                new StubBm25(List.of(hit("kb-a"))), null, props(KnowledgeMode.HYBRID));
        assertEquals("kb-a", withBm25Only.search("退款", 3).get(0).sourceId());
    }

    @Test
    @DisplayName("融合结果按条数截断，不把两路的条数简单相加")
    void shouldRespectLimitWhenFusing() {
        StubBm25 bm25 = new StubBm25(List.of(hit("kb-a"), hit("kb-b"), hit("kb-c")));
        StubVector vector = new StubVector(List.of(hit("kb-d"), hit("kb-e")));
        KnowledgeSearchRouter router = new KnowledgeSearchRouter(bm25, vector, props(KnowledgeMode.HYBRID));

        assertEquals(2, router.search("退款", 2).size());
    }

    @Test
    @DisplayName("空结果不算失败：两路都返回空就是「没有这条知识」")
    void shouldReturnEmptyWhenBothEmpty() {
        StubBm25 bm25 = new StubBm25(List.of());
        StubVector vector = new StubVector(List.of());
        KnowledgeSearchRouter router = new KnowledgeSearchRouter(bm25, vector, props(KnowledgeMode.HYBRID));

        assertTrue(router.search("开发票", 3).isEmpty());
    }

    @Test
    @DisplayName("融合时同一文档只出现一次，分数取两路之和")
    void shouldDeduplicateWhenFusing() {
        StubBm25 bm25 = new StubBm25(List.of(hit("kb-a"), hit("kb-b")));
        StubVector vector = new StubVector(List.of(hit("kb-a"), hit("kb-c")));
        KnowledgeSearchRouter router = new KnowledgeSearchRouter(bm25, vector, props(KnowledgeMode.HYBRID));

        List<KnowledgeHit> hits = router.search("退款", 5);

        List<String> ids = new ArrayList<>(hits.stream().map(KnowledgeHit::sourceId).toList());
        assertEquals(List.of("kb-a", "kb-b", "kb-c"), ids);
        // k=60: 第一位 1/61，第二位 1/62
        assertEquals(1.0 / 61 + 1.0 / 61, hits.get(0).score(), 1e-9);
    }
}
