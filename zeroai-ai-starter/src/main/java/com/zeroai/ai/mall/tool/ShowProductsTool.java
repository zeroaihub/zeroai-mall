package com.zeroai.ai.mall.tool;

import com.zeroai.ai.mall.event.GuideUiEvents;
import com.zeroai.ai.mall.event.ProductCards;
import com.zeroai.domain.api.goods.entity.Goods;
import com.zeroai.domain.api.goods.service.IGoodsService;
import io.agentscope.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 只读工具：把「AI 挑中的商品」回显成卡片。
 * <p>
 * <b>为什么单独做一个工具</b>：搜索只负责召回候选池，真正「哪几件符合用户条件」是模型判断的。
 * 如果让搜索工具直接推卡片，页面就会把整个候选池（含「地毯」「摆件」这类只沾了关键词的噪声）
 * 全推给用户；反过来，如果在服务端给检索加「价格上限 / 品牌 / 用途」参数，
 * 每加一个用户能说出口的条件都要重写一遍检索链路。
 * <p>
 * 所以拆成两步：{@code search_products} 捞候选 → 模型挑选 → {@code show_products} 按 ID 回显。
 * 服务端只提供「按 ID 取商品展示」这一个原语，条件判断全部留在模型侧，
 * 新增任何筛选口径都不需要改后端。
 * <p>
 * 入参 goodsId 只能来自本轮工具返回：服务端按 ID 查库，不接受任意参数拼查询，
 * 也不返回成本价、库存编码等内部字段。
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
     * @param events       前端事件出口，可为 null（单测、MCP 复用时不推送）
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
            description = "展示商品的唯一入口，不调用用户看不到卡片和加购按钮。"
                    + "goodsIds 用逗号分隔（取自 search_products），如 1181016,1030003，最多 3 个。",
            readOnly = true,
            concurrencySafe = true)
    public String showProducts(String goodsIds) {
        // 入参用字符串而不是 List<Long>：SDK 的参数绑定对集合类型不稳定，
        // 模型常把它写成 "1181016,1030003" 或 "[1181016]"，这里统一解析，避免调用在绑定阶段就失败
        List<Long> parsed = parseIds(goodsIds);
        if (parsed.isEmpty()) {
            return "{\"error\":\"EMPTY_GOODS_IDS\",\"hint\":\"请把商品ID用逗号分隔传入，如 1181016,1030003\"}";
        }
        return showProducts(parsed);
    }

    /**
     * 按商品 ID 回显卡片（内部入口，单测与 MCP 可直接调）。
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
            sb.append("{\"goodsId\":").append(ToolJson.num(card.goodsId()))
                    .append(",\"name\":").append(ToolJson.str(card.name()))
                    .append(",\"retailPrice\":").append(ToolJson.num(card.price()))
                    .append(",\"sales\":").append(ToolJson.num(card.sales()))
                    .append('}');
        }
        sb.append(']');
        if (!skipped.isEmpty()) {
            sb.append(",\"skipped\":").append(skipped);
        }
        if (truncated) {
            sb.append(",\"truncated\":true");
        }
        sb.append(",\"hint\":").append(ToolJson.str(SHOWN_HINT));
        return sb.append('}').toString();
    }

    /**
     * 解析模型传来的商品 ID 串。
     * <p>
     * 兼容三种写法：{@code 1181016,1030003}、{@code [1181016, 1030003]}、{@code 1181016 1030003}。
     * 非数字片段直接忽略，由调用方在结果为空时给出格式提示。
     *
     * @param raw 原始入参，可为 null
     * @return 解析出的 ID 列表，保持原顺序
     */
    static List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String normalized = raw.replaceAll("[\\[\\]\"']", ",");
        List<Long> ids = new java.util.ArrayList<>();
        for (String part : normalized.split("[,，、;；/\\s]+")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(token));
            } catch (NumberFormatException ignored) {
                // 非数字片段（如误传商品名）忽略，交给空结果分支提示格式
            }
        }
        return ids;
    }
}
