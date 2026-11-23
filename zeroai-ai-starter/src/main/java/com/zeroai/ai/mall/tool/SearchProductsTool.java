package com.zeroai.ai.mall.tool;

import com.zeroai.ai.mall.port.MallSearchPort;
import io.agentscope.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 只读工具：商品搜索。
 * <p>
 * 安全边界：方法体内不含任何写操作，不触发库存冻结、不加购、不创建订单。
 * 实现方为入口模块提供的 {@link MallSearchPort}，与 H5 搜索走同一条检索链路。
 * <p>
 * <b>本工具只负责「把候选捞回来」，不做任何筛选判断。</b>
 * 哪个商品真正符合用户的预算、品牌、用途，由模型在候选里挑，挑中后调用
 * {@link ShowProductsTool} 回显卡片。
 * <p>
 * 为什么不加「价格上限 / 品牌 / 类目」这类检索参数：每增加一个用户能说出口的条件，
 * 都要在检索接口、ES 查询、前端表单里各实现一遍，而模型本来就读得懂自然语言条件。
 * 交给模型判断，服务端只提供「按 ID 回显」的原语，链路才不会随着条件增加而膨胀。
 * <p>
 * <b>本实例持有「本轮已搜次数」状态，必须按请求创建。</b>
 * {@code MallAgentFactory} 每次请求都会 new 一个，不能改成单例复用——
 * 复用会让次数统计跨用户累计，第三个用户一开始就被判定超限。
 * <p>
 * 为什么要计数：基线里「5000 元以内的手机」只命中 1 条，模型连搜了 5 次不同关键词，
 * 把单轮步数顶到 6 步上限、token 翻了 2 倍，最终答案却没变好。
 * 这件事仅靠 prompt 约束不可靠，需要一个服务端的确定性止损点。
 */
public class SearchProductsTool {

    private static final Logger log = LoggerFactory.getLogger(SearchProductsTool.class);

    /** 单次返回条数上限，防止上下文被商品列表撑爆。 */
    private static final int MAX_LIMIT = 10;

    /** 默认返回条数。留足候选池，供模型按用户条件自行挑。 */
    private static final int DEFAULT_LIMIT = 5;

    /** 单轮搜索次数的缺省上限。 */
    public static final int DEFAULT_MAX_SEARCHES = 3;

    /** 命中后的下一步：模型常把「文字说清楚」当成任务完成，忘了展示卡片。 */
    private static final String NEXT_HINT =
            "从中挑出真正符合用户条件的商品，调用 show_products(goodsIds) 展示卡片；不符合的不要传";

    /** 命中过少时的止损提示：基线里模型正是在这里反复换词。 */
    private static final String FEW_HITS_HINT =
            "命中很少：如实告诉用户只有这几件，不要再换关键词重试";

    private final MallSearchPort searchPort;

    /** 单轮允许的搜索次数。 */
    private final int maxSearches;

    /**
     * 本轮已执行的搜索次数。用原子类型是因为工具声明了 {@code concurrencySafe}，
     * SDK 可能并行调用同一个实例。
     */
    private final AtomicInteger usedSearches = new AtomicInteger();

    /**
     * @param searchPort 检索端口，由入口模块实现；允许为 null（此时搜索工具明确返回不可用）
     */
    public SearchProductsTool(MallSearchPort searchPort) {
        this(searchPort, DEFAULT_MAX_SEARCHES);
    }

    /**
     * @param searchPort  检索端口，允许为 null
     * @param maxSearches 单轮搜索次数上限，非正数时回落到 {@link #DEFAULT_MAX_SEARCHES}
     */
    public SearchProductsTool(MallSearchPort searchPort, int maxSearches) {
        this.searchPort = searchPort;
        this.maxSearches = maxSearches > 0 ? maxSearches : DEFAULT_MAX_SEARCHES;
    }

    /**
     * 按关键词搜索在售商品。
     *
     * @param keyword 用户想找的商品关键词，如「手机」「连衣裙」
     * @param limit   期望返回条数，缺省 5，最大 10
     * @return JSON 对象字符串，含 keyword / count / items（goodsId、name、retailPrice、sales）
     */
    @Tool(name = "search_products",
            description = "按关键词检索在售商品，返回候选池（ID/名称/价格/销量）。只读。"
                    + "需按用户条件自行挑选，挑中的再调 show_products 展示。",
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
        if (usedSearches.get() >= maxSearches) {
            log.info("工具拒绝 search_products keyword=\"{}\" reason=SEARCH_BUDGET_EXCEEDED used={} max={}",
                    keyword, usedSearches.get(), maxSearches);
            return "{\"error\":\"SEARCH_BUDGET_EXCEEDED\",\"searched\":" + usedSearches.get()
                    + ",\"hint\":\"本轮搜索次数已用完，不要再调用搜索：请用已有结果回答，"
                    + "或直接告诉用户没找到并建议换分类\"}";
        }
        usedSearches.incrementAndGet();
        int size = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        log.info("工具调用 search_products keyword=\"{}\" limit={} attempt={}/{}",
                keyword.trim(), size, usedSearches.get(), maxSearches);

        List<MallSearchPort.GoodsBrief> hits;
        try {
            hits = searchPort.search(keyword.trim(), size);
        } catch (RuntimeException e) {
            // 检索故障不抛给模型，转为可读的 observation，由模型决定是否降级话术
            log.warn("工具失败 search_products keyword=\"{}\" error={}", keyword.trim(), e.getMessage());
            return "{\"error\":\"SEARCH_UNAVAILABLE\",\"hint\":\"检索服务暂时不可用，请提示用户稍后再试或用分类浏览\"}";
        }
        if (hits == null) {
            hits = List.of();
        }
        log.info("工具结果 search_products keyword=\"{}\" hits={} goodsIds={}",
                keyword.trim(), hits.size(),
                hits.stream().map(MallSearchPort.GoodsBrief::goodsId).toList());

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
        sb.append(']');
        if (hits.size() <= 1) {
            sb.append(",\"hint\":").append(ToolJson.str(FEW_HITS_HINT));
        }
        if (!hits.isEmpty()) {
            sb.append(",\"next\":").append(ToolJson.str(NEXT_HINT));
        }
        return sb.append('}').toString();
    }
}
