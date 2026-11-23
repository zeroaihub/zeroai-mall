package com.zeroai.ai.mall.tool;

import com.zeroai.ai.mall.event.GuideUiEvents;
import com.zeroai.ai.mall.event.ProductCards;
import com.zeroai.domain.api.goods.entity.Goods;
import com.zeroai.domain.api.goods.service.IGoodsService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 商品回显工具的行为测试。
 * <p>
 * 这个工具的职责边界很窄：只按 AI 给的 ID 取商品并推卡片，
 * 不做任何「谁匹配、谁不匹配」的判断——那是模型的事。
 * 因此用例只断言三件事：顺序、有效性过滤、不越权编造商品。
 */
class ShowProductsToolTest {

    private static Goods goods(Long id, String name, String price, boolean onSale) {
        return Goods.builder()
                .id(id)
                .name(name)
                .retailPrice(new BigDecimal(price))
                .actualSales(10)
                .picUrl("http://img/" + id + ".png")
                .isOnSale(onSale)
                .delFlag(false)
                .build();
    }

    /** 记录一次卡片推送，便于断言顺序与数量。 */
    private static final class CardSink implements GuideUiEvents {
        private ProductCards last;

        @Override
        public void onProductCards(ProductCards cards) {
            this.last = cards;
        }
    }

    @Test
    void shouldShowOnlySelectedGoodsInGivenOrder() {
        IGoodsService goodsService = mock(IGoodsService.class);
        // 库里的顺序与模型挑选的顺序无关，回显必须按模型给的顺序
        when(goodsService.listByIds(anyCollection())).thenReturn(List.of(
                goods(2L, "苹果手机", "5999.00", true),
                goods(1L, "小米手机", "2999.00", true)));
        CardSink sink = new CardSink();

        String result = new ShowProductsTool(goodsService, sink).showProducts(List.of(1L, 2L));

        assertTrue(result.contains("\"count\":2"), result);
        assertEquals(List.of("小米手机", "苹果手机"),
                sink.last.items().stream().map(ProductCards.Card::name).toList(),
                "卡片顺序应与模型挑选顺序一致");
    }

    @Test
    void shouldSkipOfflineOrMissingGoods() {
        IGoodsService goodsService = mock(IGoodsService.class);
        when(goodsService.listByIds(anyCollection())).thenReturn(List.of(
                goods(1L, "在售手机", "2999.00", true),
                goods(2L, "已下架手机", "1999.00", false)));
        CardSink sink = new CardSink();

        String result = new ShowProductsTool(goodsService, sink).showProducts(List.of(1L, 2L, 99L));

        assertTrue(result.contains("\"count\":1"), result);
        assertTrue(result.contains("skipped"), "下架与不存在的 ID 要回报给模型：" + result);
        assertEquals(List.of("在售手机"),
                sink.last.items().stream().map(ProductCards.Card::name).toList());
    }

    @Test
    void shouldFailLoudlyWhenAllGoodsUnavailable() {
        IGoodsService goodsService = mock(IGoodsService.class);
        when(goodsService.listByIds(anyCollection())).thenReturn(List.of());
        CardSink sink = new CardSink();

        String result = new ShowProductsTool(goodsService, sink).showProducts(List.of(99L));

        assertTrue(result.contains("GOODS_NOT_AVAILABLE"), result);
        assertEquals(null, sink.last, "没有可展示商品时不得下发空卡片");
    }

    @Test
    void shouldRejectEmptyIds() {
        String result = new ShowProductsTool(mock(IGoodsService.class), GuideUiEvents.NOOP)
                .showProducts(List.of());
        assertTrue(result.contains("EMPTY_GOODS_IDS"), result);
        assertTrue(new ShowProductsTool(mock(IGoodsService.class), GuideUiEvents.NOOP)
                .showProducts((String) null).contains("EMPTY_GOODS_IDS"));
    }

    @Test
    void shouldCapAtThreeAndReportTruncation() {
        IGoodsService goodsService = mock(IGoodsService.class);
        List<Goods> all = new ArrayList<>();
        for (long id = 1; id <= 7; id++) {
            all.add(goods(id, "手机" + id, "1999.00", true));
        }
        when(goodsService.listByIds(anyCollection())).thenReturn(all);
        CardSink sink = new CardSink();

        String result = new ShowProductsTool(goodsService, sink)
                .showProducts(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L));

        assertEquals(3, sink.last.items().size(), "一屏最多 3 条");
        assertTrue(result.contains("\"truncated\":true"), result);
        assertFalse(result.contains("\"count\":7"), result);
    }

    @Test
    void shouldParseIdsFromModelFormats() {
        // SDK 传字符串时，模型可能写成逗号分隔、带方括号、或空格分隔
        assertEquals(List.of(1181016L, 1030003L), IdListParser.parse("1181016,1030003"));
        assertEquals(List.of(1181016L), IdListParser.parse("[1181016]"));
        assertEquals(List.of(1181016L, 1030003L), IdListParser.parse("1181016 1030003"));
        assertEquals(List.of(), IdListParser.parse("小米手机"), "非数字片段应忽略");
        assertEquals(List.of(), IdListParser.parse(""));
    }

    @Test
    void shouldHandleStringEntrypoint() {
        IGoodsService goodsService = mock(IGoodsService.class);
        when(goodsService.listByIds(anyCollection()))
                .thenReturn(List.of(goods(1L, "小米手机", "2999.00", true)));
        CardSink sink = new CardSink();

        String result = new ShowProductsTool(goodsService, sink).showProducts("1");

        assertTrue(result.contains("\"count\":1"), result);
        assertTrue(new ShowProductsTool(goodsService, sink).showProducts("小米手机")
                .contains("EMPTY_GOODS_IDS"), "纯文本入参要给出格式提示，而不是静默失败");
    }

    @Test
    void shouldNotEmitCardsWhenEventsMissing() {
        IGoodsService goodsService = mock(IGoodsService.class);
        when(goodsService.listByIds(anyCollection()))
                .thenReturn(List.of(goods(1L, "小米手机", "2999.00", true)));

        // events 传 null 必须能正常返回：MCP 等无推送场景复用同一工具
        String result = new ShowProductsTool(goodsService, null).showProducts(List.of(1L));

        assertTrue(result.contains("\"count\":1"), result);
    }
}
