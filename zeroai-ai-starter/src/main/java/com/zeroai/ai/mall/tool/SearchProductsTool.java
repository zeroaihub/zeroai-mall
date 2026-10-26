package com.zeroai.ai.mall.tool;

import com.zeroai.ai.mall.port.MallSearchPort;
import io.agentscope.core.tool.Tool;

import java.util.List;

/**
 * 只读工具：商品搜索。
 * <p>
 * 安全边界：方法体内不含任何写操作，不触发库存冻结、不加购、不创建订单。
 * 实现方为入口模块提供的 {@link MallSearchPort}，与 H5 搜索走同一条检索链路。
 */
public class SearchProductsTool {

    /** 单次返回条数上限，防止上下文被商品列表撑爆。 */
    private static final int MAX_LIMIT = 10;

    /** 默认返回条数。 */
    private static final int DEFAULT_LIMIT = 5;

    private final MallSearchPort searchPort;

    /**
     * @param searchPort 检索端口，由入口模块实现；允许为 null（此时搜索工具明确返回不可用）
     */
    public SearchProductsTool(MallSearchPort searchPort) {
        this.searchPort = searchPort;
    }

    /**
     * 按关键词搜索在售商品。
     *
     * @param keyword 用户想找的商品关键词，如「手机」「连衣裙」
     * @param limit   期望返回条数，缺省 5，最大 10
     * @return JSON 数组字符串，每个元素含 goodsId / name / retailPrice / sales
     */
    @Tool(name = "search_products",
            description = "按关键词检索在售商品，返回商品ID、名称、价格、销量。只读操作，不修改任何数据。",
            readOnly = true,
            concurrencySafe = true)
    public String searchProducts(String keyword, Integer limit) {
        if (searchPort == null) {
            // 入口模块未提供检索实现时的显式降级，不静默返回空结果误导模型
            return "{\"error\":\"SEARCH_NOT_WIRED\",\"hint\":\"检索能力未接入，请提示用户用分类浏览\"}";
        }
        if (keyword == null || keyword.isBlank()) {
            return "{\"error\":\"EMPTY_KEYWORD\",\"hint\":\"缺少检索关键词，请先向用户确认想找什么\"}";
        }
        int size = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);

        List<MallSearchPort.GoodsBrief> hits;
        try {
            hits = searchPort.search(keyword.trim(), size);
        } catch (RuntimeException e) {
            // 检索故障不抛给模型，转为可读的 observation，由模型决定是否降级话术
            return "{\"error\":\"SEARCH_UNAVAILABLE\",\"hint\":\"检索服务暂时不可用，请提示用户稍后再试或用分类浏览\"}";
        }

        StringBuilder sb = new StringBuilder(64 + hits.size() * 96);
        sb.append("{\"keyword\":").append(ToolJson.str(keyword.trim()))
                .append(",\"count\":").append(hits.size())
                .append(",\"items\":[");
        for (int i = 0; i < hits.size(); i++) {
            MallSearchPort.GoodsBrief item = hits.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"goodsId\":").append(ToolJson.num(item.goodsId()))
                    .append(",\"name\":").append(ToolJson.str(item.name()))
                    .append(",\"retailPrice\":").append(ToolJson.num(item.retailPrice()))
                    .append(",\"sales\":").append(ToolJson.num(item.sales()))
                    .append('}');
        }
        return sb.append("]}").toString();
    }
}
