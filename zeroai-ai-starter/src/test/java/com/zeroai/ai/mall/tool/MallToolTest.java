package com.zeroai.ai.mall.tool;

import com.zeroai.ai.mall.port.MallSearchPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只读工具的行为测试：越权防护、降级、返回值可解析性。
 * <p>
 * 这些用例不依赖 Spring 容器，也不访问数据库，
 * 目的是把「安全边界」固定成断言，避免后续重构时被悄悄改掉。
 */
class MallToolTest {

    @Test
    void orderToolMustRejectNullUser() {
        assertThrows(IllegalArgumentException.class, () -> new MyOrdersTool(null, null));
    }

    @Test
    void searchToolShouldDegradeWhenPortMissing() {
        String result = new SearchProductsTool(null).searchProducts("手机", 5);
        assertTrue(result.contains("SEARCH_NOT_WIRED"), "未接入检索时必须显式降级，不能返回空结果冒充无货");
        assertTrue(result.contains("error"));
    }

    @Test
    void searchToolShouldRejectBlankKeyword() {
        // 用真实端口，验证的是参数校验分支；端口缺失的降级由上一个用例覆盖
        MallSearchPort port = (keyword, limit) -> List.of();
        String result = new SearchProductsTool(port).searchProducts("   ", 5);
        assertTrue(result.contains("EMPTY_KEYWORD"));
    }

    @Test
    void searchToolShouldClampLimitToMax() {
        int[] captured = new int[1];
        MallSearchPort port = (keyword, limit) -> {
            captured[0] = limit;
            return List.of();
        };
        new SearchProductsTool(port).searchProducts("手机", 999);
        assertTrue(captured[0] <= 10, "上限必须收敛到 10，实际=" + captured[0]);
    }

    @Test
    void searchToolShouldEmitParsableJsonWithEscapedContent() {
        MallSearchPort port = (keyword, limit) -> List.of(
                new MallSearchPort.GoodsBrief(1030003L, "手机 \"Pro\"", new BigDecimal("3999.00"), 12, "http://x/y.png"));
        String result = new SearchProductsTool(port).searchProducts("手机", 5);

        assertTrue(result.startsWith("{"));
        assertTrue(result.endsWith("}"));
        assertTrue(result.contains("\"count\":1"));
        assertTrue(result.contains("\\\"Pro\\\""), "引号必须转义，否则 JSON 会被模型解析失败");
        assertTrue(result.contains("1030003"));
    }

    @Test
    void detailToolShouldRejectNullGoodsId() {
        String result = new GoodsDetailTool(null).getProductDetail(null);
        assertTrue(result.contains("MISSING_GOODS_ID"));
    }

    @Test
    void stockToolShouldRejectNullSkuId() {
        String result = new StockStatusTool(null).getStockStatus(null);
        assertTrue(result.contains("MISSING_SKU_ID"));
    }

    // ---------- 单轮搜索预算：把「反复换关键词重试」变成确定性止损 ----------

    @Test
    void searchToolShouldStopAfterBudget() {
        int[] calls = new int[1];
        MallSearchPort port = (keyword, limit) -> {
            calls[0]++;
            return List.of();
        };
        SearchProductsTool tool = new SearchProductsTool(port, 2);

        tool.searchProducts("手机", 5);
        tool.searchProducts("平板", 5);
        String third = tool.searchProducts("耳机", 5);

        assertTrue(third.contains("SEARCH_BUDGET_EXCEEDED"), third);
        assertEquals(2, calls[0], "超出预算后不得再访问检索服务，否则等于没止损");
    }

    @Test
    void searchToolShouldHintStopWhenHitsAreFew() {
        MallSearchPort empty = (keyword, limit) -> List.of();
        assertTrue(new SearchProductsTool(empty).searchProducts("手机", 5).contains("命中很少"));

        MallSearchPort single = (keyword, limit) -> List.of(
                new MallSearchPort.GoodsBrief(1L, "只有一件", new BigDecimal("99.00"), 1, null));
        assertTrue(new SearchProductsTool(single).searchProducts("手机", 5).contains("命中很少"));

        MallSearchPort plenty = (keyword, limit) -> List.of(
                new MallSearchPort.GoodsBrief(1L, "A", new BigDecimal("99.00"), 1, null),
                new MallSearchPort.GoodsBrief(2L, "B", new BigDecimal("199.00"), 2, null));
        assertFalse(new SearchProductsTool(plenty).searchProducts("手机", 5).contains("命中很少"));
    }

    @Test
    void searchBudgetIsPerInstanceNotGlobal() {
        // 工具按请求创建：预算必须随实例重置，否则第二个用户开局就被判定超限
        MallSearchPort port = (keyword, limit) -> List.of();
        new SearchProductsTool(port, 1).searchProducts("手机", 5);

        String otherRequest = new SearchProductsTool(port, 1).searchProducts("手机", 5);

        assertFalse(otherRequest.contains("SEARCH_BUDGET_EXCEEDED"), otherRequest);
    }
}
