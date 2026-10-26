package com.zeroai.ai.mall.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zeroai.domain.api.cart.entity.Cart;
import com.zeroai.domain.api.cart.service.ICartService;
import io.agentscope.core.tool.Tool;

import java.util.List;

/**
 * 只读工具：当前用户的购物车。
 * <p>
 * 越权防护：userId 构造期注入；查询条件里带上 userId，跨用户购物车天然查不到。
 * 本工具只读，不改勾选状态、不改数量、不加购。
 */
public class ListMyCartTool {

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
            description = "查询当前登录用户本人的购物车商品，返回 cartId、商品名、单价、数量与勾选状态。只读操作，不修改购物车。",
            readOnly = true,
            concurrencySafe = true)
    public String listMyCart(Integer limit) {
        int size = limit == null ? 5 : Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<Cart> carts;
        try {
            carts = cartService.list(Wrappers.lambdaQuery(Cart.class)
                    .eq(Cart::getUserId, userId)
                    .last("LIMIT " + size));
        } catch (RuntimeException e) {
            return "{\"error\":\"CART_UNAVAILABLE\",\"hint\":\"购物车查询失败，请稍后再试\"}";
        }
        if (carts == null || carts.isEmpty()) {
            return "{\"count\":0,\"items\":[],\"hint\":\"购物车是空的，可以先搜索商品\"}";
        }
        StringBuilder sb = new StringBuilder(128 + carts.size() * 128);
        sb.append("{\"count\":").append(carts.size()).append(",\"items\":[");
        for (int i = 0; i < carts.size(); i++) {
            Cart cart = carts.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"cartId\":").append(ToolJson.num(cart.getId()))
                    .append(",\"goodsName\":").append(ToolJson.str(cart.getGoodsName()))
                    .append(",\"productId\":").append(ToolJson.num(cart.getProductId()))
                    .append(",\"unitPrice\":").append(ToolJson.num(cart.getPrice()))
                    .append(",\"number\":").append(ToolJson.num(cart.getNumber()))
                    .append(",\"checked\":").append(Boolean.TRUE.equals(cart.getChecked()))
                    .append('}');
        }
        return sb.append("]}").toString();
    }
}
