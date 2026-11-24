package com.zeroai.ai.engine.langchain4j.tool;

import com.zeroai.ai.mall.port.MallSearchPort;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 只读工具：商品搜索（LangChain4j 版）。
 * <p>
 * 行为与 {@code com.zeroai.ai.mall.tool.SearchProductsTool}（AgentScope 版）完全一致：
 * 只负责把候选捞回来，不做任何筛选判断；模型挑中后调 {@code show_products} 回显卡片。
 * 实例持有「本轮已搜次数」状态，<b>必须按请求创建</b>，不能单例复用。
 * <p>
 * 差异仅在框架适配层：{@code @Tool}/{@code @P} 是 LangChain4j 注解，
 * 没有 AgentScope 的 readOnly/concurrencySafe 属性（该框架无此概念）。
 * <b>两边方法体必须同步修改</b>，见 ADR-003。
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

    /** 命中过少时的止损提示。 */
    private static final String FEW_HITS_HINT =
            "命中很少：如实告诉用户只有这几件，不要再换关键词重试";

    private final MallSearchPort searchPort;

    /** 单轮允许的搜索次数。 */
    private final int maxSearches;

    /** 本轮已执行的搜索次数。 */
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
            value = {"按关键词检索在售商品，返回候选池（ID/名称/价格/销量）。只读。",
                    "需按用户条件自行挑选，挑中的再调 show_products 展示。"})
    public String searchProducts(@P("商品关键词，只放品类词或品牌词，如「手机」") String keyword,
                                 @P(value = "期望返回条数，缺省 5，最大 10", required = false) Integer limit) {
        if (searchPort == null) {
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
        sb.append("{\"keyword\":").append(JsonText.str(keyword.trim()))
                .append(",\"count\":").append(hits.size())
                .append(",\"items\":[");
        for (int i = 0; i < hits.size(); i++) {
            MallSearchPort.GoodsBrief item = hits.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"goodsId\":").append(JsonText.num(item.goodsId()))
                    .append(",\"name\":").append(JsonText.str(item.name()))
                    .append(",\"retailPrice\":").append(JsonText.num(item.retailPrice()))
                    .append(",\"sales\":").append(JsonText.num(item.sales()))
                    .append('}');
        }
        sb.append(']');
        if (hits.size() <= 1) {
            sb.append(",\"hint\":").append(JsonText.str(FEW_HITS_HINT));
        }
        if (!hits.isEmpty()) {
            sb.append(",\"next\":").append(JsonText.str(NEXT_HINT));
        }
        return sb.append('}').toString();
    }
}
