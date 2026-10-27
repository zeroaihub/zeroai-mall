package com.zeroai.ai.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索模式解析测试。
 * <p>
 * 这一层的价值在于「配置写错时的行为」：解析失败必须回落到依赖最少的模式，
 * 而不是让服务起不来，也不能落到一个「看起来开启但实际查不到」的状态。
 */
class KnowledgeModeTest {

    @Test
    @DisplayName("三种合法取值都能解析，且大小写与空白不敏感")
    void shouldParseKnownValues() {
        assertEquals(KnowledgeMode.BM25, KnowledgeMode.from("bm25"));
        assertEquals(KnowledgeMode.VECTOR, KnowledgeMode.from("VECTOR"));
        assertEquals(KnowledgeMode.HYBRID, KnowledgeMode.from("  Hybrid  "));
    }

    @Test
    @DisplayName("空值与未知值一律回落 BM25")
    void shouldFallbackToBm25() {
        assertEquals(KnowledgeMode.BM25, KnowledgeMode.from(null));
        assertEquals(KnowledgeMode.BM25, KnowledgeMode.from(""));
        assertEquals(KnowledgeMode.BM25, KnowledgeMode.from("   "));
        // 拼错的配置不能让服务起不来，也不能落到「没有任何实现」的模式
        assertEquals(KnowledgeMode.BM25, KnowledgeMode.from("ernie"));
        assertEquals(KnowledgeMode.BM25, KnowledgeMode.from("向量"));
    }

    @Test
    @DisplayName("只有向量与混合模式需要 embedding 服务")
    void shouldTellWhichModesNeedEmbedding() {
        assertFalse(KnowledgeMode.BM25.needsEmbedding(), "纯 BM25 不该产生任何 embedding 调用");
        assertTrue(KnowledgeMode.VECTOR.needsEmbedding());
        assertTrue(KnowledgeMode.HYBRID.needsEmbedding());
    }

    @Test
    @DisplayName("isKnown 能区分「没配」与「配错」")
    void shouldDistinguishMissingAndUnknown() {
        assertTrue(KnowledgeMode.isKnown(null), "null 表示没配，按默认值处理，不算错");
        assertTrue(KnowledgeMode.isKnown("bm25"));
        assertTrue(KnowledgeMode.isKnown("HYBRID"));
        assertFalse(KnowledgeMode.isKnown("vector-rag"));
    }

    @Test
    @DisplayName("配置值可往返")
    void shouldRoundTripCodes() {
        for (KnowledgeMode mode : KnowledgeMode.values()) {
            assertEquals(mode, KnowledgeMode.from(mode.code()));
        }
    }
}
