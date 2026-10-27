package com.zeroai.ai.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 查询串归一化测试。
 * <p>
 * 重点是「两种强度不能混用」：{@code clean} 保留语义给向量检索，
 * {@code stripNoise} 剔除虚词给倒排索引。一旦两者合并，切模式必然有一边吃亏。
 */
class KnowledgeQueryNormalizerTest {

    @Test
    @DisplayName("clean 只压缩空白与去零宽字符，不改写语义")
    void cleanShouldKeepSemantics() {
        assertEquals("在哪里查看我的订单",
                KnowledgeQueryNormalizer.clean("  在哪里查看我的订单  ", 80));
        assertEquals("退款 多久到账",
                KnowledgeQueryNormalizer.clean("退款 \t 多久到账\r\n", 80));
        assertEquals("退款多久到账",
                KnowledgeQueryNormalizer.clean("退款\u200B多久\uFEFF到账", 80),
                "零宽字符会让「看起来一样」的问题检索不到");
    }

    @Test
    @DisplayName("clean 按上限截断，null 返回空串")
    void cleanShouldTruncate() {
        assertEquals("退款", KnowledgeQueryNormalizer.clean("退款多久到账", 2));
        assertEquals("", KnowledgeQueryNormalizer.clean(null, 80));
        assertEquals("", KnowledgeQueryNormalizer.clean("   ", 80));
        assertEquals("退款多久到账", KnowledgeQueryNormalizer.clean("退款多久到账", 0), "0 表示不限制");
    }

    @Test
    @DisplayName("stripNoise 剔除疑问词与口头语，保留业务词")
    void stripNoiseShouldRemoveFillers() {
        // 空格是刻意的分词分隔符：ik 对空白敏感，不影响切词
        assertEquals("查看 订单", KnowledgeQueryNormalizer.stripNoise("在哪里查看我的订单", 80));
        // 「请问」「呢」被剔掉；「多久」「能」不在噪声表里 —— 它们是语义的一部分，不能剔
        assertEquals("退款多久能到账", KnowledgeQueryNormalizer.stripNoise("请问退款多久能到账呢", 80));
        assertEquals("运费 算", KnowledgeQueryNormalizer.stripNoise("运费怎么算的", 80));
    }

    @Test
    @DisplayName("stripNoise 不碰业务词：退款、运费、优惠券必须留着")
    void stripNoiseShouldKeepBusinessWords() {
        assertEquals("退款", KnowledgeQueryNormalizer.stripNoise("退款", 80));
        assertEquals("优惠券门槛", KnowledgeQueryNormalizer.stripNoise("优惠券门槛是什么", 80),
                "「什么」「是」是虚词，业务词必须完整保留");
        assertEquals("包邮和运费有 区别", KnowledgeQueryNormalizer.stripNoise("包邮和运费有什么区别", 80),
                "只剔除「什么」，不碰「包邮」「运费」");
    }

    @Test
    @DisplayName("全被剔空时回退到未剔除的结果，不构造空查询")
    void stripNoiseShouldFallbackWhenEverythingRemoved() {
        // 只问了「怎么办」：剔完什么都不剩，此时宁可把原句交给检索层，也不要发一个空查询
        assertEquals("怎么办", KnowledgeQueryNormalizer.stripNoise("怎么办", 80));
        assertEquals("", KnowledgeQueryNormalizer.stripNoise(null, 80));
        assertEquals("", KnowledgeQueryNormalizer.stripNoise("   ", 80));
    }

    @Test
    @DisplayName("stripNoise 同样按上限截断")
    void stripNoiseShouldTruncate() {
        assertEquals("运费", KnowledgeQueryNormalizer.stripNoise("运费怎么算", 2));
    }
}
