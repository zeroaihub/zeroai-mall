package com.zeroai.ai.engine.langchain4j.tool;

import com.zeroai.ai.mall.event.GuideUiEvents;
import com.zeroai.ai.mall.event.ProductCards;
import com.zeroai.domain.api.goods.entity.Goods;
import com.zeroai.domain.api.goods.service.IGoodsService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 只读工具：把「AI 挑中的商品」回显成卡片（LangChain4j 版）。
 * <p>
 * 行为与 {@code com.zeroai.ai.mall.tool.ShowProductsTool}（AgentScope 版）完全一致：
 * 搜索捞候选 → 模型挑选 → 本工具按 goodsId 回显卡片并下发 {@code product_cards} 事件，
 * 服务端只提供「按 ID 取商品展示」这一个原语。单次最多 3 条，按传入顺序回显，
 * 下架/不存在的进 {@code skipped}。<b>两边方法体必须同步修改</b>，见 ADR-003。
 */
public class ShowProductsTool {

    private static final Logger log = LoggerFactory.getLogger(ShowProductsTool.class);

    /** 单次最多展示的商品数：卡片是候选清单，不是搜索列表页，一屏三条最舒服。 */
    private static final int MAX_IDS = 3;

    /** 展示成功后的固定提示，避免模型在正文里再抄一遍价格清单。 */
    private static final String SHOWN_HINT =
            "卡片已展示，正文不要再重复罗列名称价格，一句话说结论和差异即可";

    private final IGoodsService goodsService;
    private final GuideUiEvents events;

    /**
     * @param goodsService 商品服务，仅用于按 ID 批量读取展示字段
     * @param events       前端事件出口，可为 null（单测复用时不推送）
     */
    public ShowProductsTool(IGoodsService goodsService, GuideUiEvents events) {
        this.goodsService = goodsService;
        this.events = events == null ? GuideUiEvents.NOOP : events;
    }

    /**
     * 把挑中的商品展示成可加购的卡片。
     *
     * @param goodsIds 已挑选的商品 ID，只能取自本轮 search_products / get_product_detail 的返回
     * @return JSON 对象字符串，含 count / items（已展示）/ skipped（已下架或不存在）
     */
    @Tool(name = "show_products",
            value = {"展示商品的唯一入口，不调用用户看不到卡片和加购按钮。",
                    "goodsIds 用逗号分隔（取自 search_products），如 1181016,1030003，最多 3 个。"})
    public String showProducts(@P("已挑选的商品ID，逗号分隔，如 1181016,1030003") String goodsIds) {
        // ID 列表走字符串 + 解析器：与 AgentScope 版行为完全对齐，避免集合绑定差异
        List<Long> parsed = IdListParser.parse(goodsIds);
        if (parsed.isEmpty()) {
            return "{\"error\":\"EMPTY_GOODS_IDS\",\"hint\":\"请把商品ID用逗号分隔传入，如 1181016,1030003\"}";
        }
        return showProducts(parsed);
    }

    /**
     * 按商品 ID 回显卡片（内部入口，单测可直接调）。
     *
     * @param goodsIds 商品 ID 列表
     * @return JSON 对象字符串
     */
    public String showProducts(List<Long> goodsIds) {
        if (goodsIds == null || goodsIds.isEmpty()) {
            return "{\"error\":\"EMPTY_GOODS_IDS\",\"hint\":\"请先调用 search_products 拿到商品ID，再展示\"}";
        }
        List<Long> ids = goodsIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_IDS)
                .toList();
        if (ids.isEmpty()) {
            return "{\"error\":\"EMPTY_GOODS_IDS\",\"hint\":\"商品ID不能为空\"}";
        }
        boolean truncated = goodsIds.stream().filter(Objects::nonNull).distinct().count() > ids.size();
        log.info("工具调用 show_products goodsIds={} truncated={}", ids, truncated);

        List<Goods> goodsList;
        try {
            goodsList = goodsService.listByIds(ids);
        } catch (RuntimeException e) {
            log.warn("工具失败 show_products goodsIds={} error={}", ids, e.getMessage());
            return "{\"error\":\"GOODS_UNAVAILABLE\",\"hint\":\"商品信息查询失败，请改为文字说明\"}";
        }
        Map<Long, Goods> byId = goodsList == null ? Map.of() : goodsList.stream()
                .collect(Collectors.toMap(Goods::getId, goods -> goods, (first, second) -> first,
                        LinkedHashMap::new));

        // 按模型给的顺序回显：它通常按推荐优先级排过序
        List<ProductCards.Card> cards = new ArrayList<>(ids.size());
        List<Long> skipped = new ArrayList<>();
        for (Long id : ids) {
            Goods goods = byId.get(id);
            if (goods == null || Boolean.TRUE.equals(goods.getDelFlag())
                    || !Boolean.TRUE.equals(goods.getIsOnSale())) {
                skipped.add(id);
                continue;
            }
            cards.add(new ProductCards.Card(goods.getId(), goods.getName(),
                    goods.getRetailPrice(), goods.getActualSales(), goods.getPicUrl()));
        }
        log.info("工具结果 show_products shown={} skipped={} names={}",
                cards.size(), skipped, cards.stream().map(ProductCards.Card::name).toList());

        if (cards.isEmpty()) {
            return "{\"error\":\"GOODS_NOT_AVAILABLE\",\"skipped\":" + skipped
                    + ",\"hint\":\"这些商品已下架或不存在，请如实告知用户并重新搜索\"}";
        }

        try {
            events.onProductCards(new ProductCards("", cards));
        } catch (RuntimeException e) {
            // 推送失败不影响工具结果：模型仍会给出文字结论
        }

        StringBuilder sb = new StringBuilder(96 + cards.size() * 80);
        sb.append("{\"count\":").append(cards.size()).append(",\"items\":[");
        for (int i = 0; i < cards.size(); i++) {
            ProductCards.Card card = cards.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"goodsId\":").append(JsonText.num(card.goodsId()))
                    .append(",\"name\":").append(JsonText.str(card.name()))
                    .append(",\"retailPrice\":").append(JsonText.num(card.price()))
                    .append(",\"sales\":").append(JsonText.num(card.sales()))
                    .append('}');
        }
        sb.append(']');
        if (!skipped.isEmpty()) {
            sb.append(",\"skipped\":").append(skipped);
        }
        if (truncated) {
            sb.append(",\"truncated\":true");
        }
        sb.append(",\"hint\":").append(JsonText.str(SHOWN_HINT));
        return sb.append('}').toString();
    }
}
