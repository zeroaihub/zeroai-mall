package com.zeroai.ai.engine.langchain4j.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zeroai.domain.api.cart.entity.Cart;
import com.zeroai.domain.api.cart.service.ICartService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 只读工具：当前用户的购物车（LangChain4j 版）。
 * <p>
 * 行为与 {@code com.zeroai.ai.mall.tool.ListMyCartTool}（AgentScope 版）完全一致：
 * userId 构造期注入，查询条件带 userId，跨用户购物车天然查不到；只读。
 * <b>两边方法体必须同步修改</b>，见 ADR-003。
 */
public class ListMyCartTool {

    private static final Logger log = LoggerFactory.getLogger(ListMyCartTool.class);

    /** 单次返回条数上限。 */
    private static final int MAX_LIMIT = 10;

    private final ICartService cartService;
    private final Long userId;

    /**
     * @param cartService 购物车服务，只读
     * @param userId      当前登录用户 ID，必须非空
     */
    public ListMyCartTool(ICartService cartService, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空：未登录用户不允许查询购物车");
        }
        this.cartService = cartService;
        this.userId = userId;
    }

    /**
     * 查询当前用户购物车商品。
     *
     * @param limit 期望返回条数，缺省 5，最大 10
     * @return JSON 字符串，含 cartId / 商品名 / 单价 / 数量 / 是否勾选
     */
    @Tool(name = "list_my_cart",
            value = "查询当前登录用户本人的购物车商品，返回 cartId、商品名、单价、数量与勾选状态。只读操作，不修改购物车。")
    public String listMyCart(@P(value = "期望返回条数，缺省 5，最大 10", required = false) Integer limit) {
        int size = limit == null ? 5 : Math.min(Math.max(limit, 1), MAX_LIMIT);
        log.info("工具调用 list_my_cart userId={} limit={}", userId, size);
        List<Cart> carts;
        try {
            carts = cartService.list(Wrappers.lambdaQuery(Cart.class)
                    .eq(Cart::getUserId, userId)
                    .last("LIMIT " + size));
        } catch (RuntimeException e) {
            log.warn("工具失败 list_my_cart userId={} error={}", userId, e.getMessage());
            return "{\"error\":\"CART_UNAVAILABLE\",\"hint\":\"购物车查询失败，请稍后再试\"}";
        }
        if (carts == null || carts.isEmpty()) {
            log.info("工具结果 list_my_cart userId={} count=0", userId);
            return "{\"count\":0,\"items\":[],\"hint\":\"购物车是空的，可以先搜索商品\"}";
        }
        StringBuilder sb = new StringBuilder(128 + carts.size() * 128);
        sb.append("{\"count\":").append(carts.size()).append(",\"items\":[");
        for (int i = 0; i < carts.size(); i++) {
            Cart cart = carts.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"cartId\":").append(JsonText.num(cart.getId()))
                    .append(",\"goodsName\":").append(JsonText.str(cart.getGoodsName()))
                    .append(",\"productId\":").append(JsonText.num(cart.getProductId()))
                    .append(",\"unitPrice\":").append(JsonText.num(cart.getPrice()))
                    .append(",\"number\":").append(JsonText.num(cart.getNumber()))
                    .append(",\"checked\":").append(Boolean.TRUE.equals(cart.getChecked()))
                    .append('}');
        }
        log.info("工具结果 list_my_cart userId={} count={} checkedCount={}", userId, carts.size(),
                carts.stream().filter(cart -> Boolean.TRUE.equals(cart.getChecked())).count());
        return sb.append("]}").toString();
    }
}
