package com.zeroai.ai.mall.tool;

import com.zeroai.ai.mall.port.MallSearchPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

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
}
