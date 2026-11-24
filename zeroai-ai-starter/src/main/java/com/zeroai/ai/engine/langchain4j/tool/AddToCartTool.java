package com.zeroai.ai.engine.langchain4j.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zeroai.ai.mall.event.CartAdded;
import com.zeroai.ai.mall.event.GuideUiEvents;
import com.zeroai.domain.api.cart.entity.Cart;
import com.zeroai.domain.api.cart.service.ICartService;
import com.zeroai.util.exception.BusinessException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 加购工具：把商品放进当前用户购物车（LangChain4j 版）。
 * <p>
 * 行为与 {@code com.zeroai.ai.mall.tool.AddToCartTool}（AgentScope 版）完全一致：
 * 只写购物车行，<b>不创订单、不冻结库存、不发起支付</b>；userId 构造期注入；
 * 写入走购物车域 {@code addDefaultGoodsProduct}（与 H5 同一路径、同一把用户锁）。
 * 加购可逆所以允许模型直接调用；订单写入仍只能由用户确认。
 * <b>两边方法体必须同步修改</b>，见 ADR-003。
 */
public class AddToCartTool {

    private static final Logger log = LoggerFactory.getLogger(AddToCartTool.class);

    /** 单次加购数量上限，防止模型把一件商品加成几十件。 */
    private static final int MAX_NUMBER = 99;

    private final ICartService cartService;
    private final Long userId;
    private final GuideUiEvents events;

    /**
     * @param cartService 购物车服务，写入走既有加购逻辑
     * @param userId      当前登录用户 ID，必须非空
     * @param events      前端事件出口，可为 null（等价于 {@link GuideUiEvents#NOOP}）
     */
    public AddToCartTool(ICartService cartService, Long userId, GuideUiEvents events) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空：未登录用户不允许加购");
        }
        this.cartService = cartService;
        this.userId = userId;
        this.events = events == null ? GuideUiEvents.NOOP : events;
    }

    /**
     * 把商品加入购物车。
     *
     * @param goodsId 商品 ID，必须来自本轮搜索结果或商品详情
     * @param number  数量，缺省 1，上限 99
     * @return JSON 字符串，含 cartId、商品名、单价、数量与下一步提示
     */
    @Tool(name = "add_to_cart",
            value = {"把指定商品加入当前登录用户的购物车，返回购物车行信息。",
                    "goodsId 必须来自本轮 search_products 或 get_product_detail 的返回；number 缺省 1。",
                    "只加购，不创建订单，下单仍需用户确认。"})
    public String addToCart(@P("商品ID，来自本轮搜索结果或商品详情") Long goodsId,
                            @P(value = "数量，缺省 1，上限 99", required = false) Integer number) {
        if (goodsId == null) {
            return "{\"error\":\"MISSING_GOODS_ID\",\"hint\":\"需要先用 search_products 找到商品再调用加购\"}";
        }
        int targetNumber = number == null ? 1 : Math.min(Math.max(number, 1), MAX_NUMBER);
        log.info("工具调用 add_to_cart userId={} goodsId={} number={}", userId, goodsId, targetNumber);
        Cart cart = new Cart();
        cart.setGoodsId(goodsId);
        cart.setNumber(targetNumber);
        try {
            // 复用购物车域入口：默认货品、上架校验、库存校验、用户维度锁都在里面
            cartService.addDefaultGoodsProduct(cart, userId);
        } catch (BusinessException e) {
            log.info("工具拒绝 add_to_cart userId={} goodsId={} reason={}", userId, goodsId, e.getMsg());
            // 业务异常文案本身就是给用户看的（已下架、库存不足等），可以回显给模型转述
            return "{\"error\":\"ADD_TO_CART_REJECTED\",\"hint\":" + JsonText.str(e.getMsg()) + "}";
        } catch (RuntimeException e) {
            log.warn("工具失败 add_to_cart userId={} goodsId={} error={}", userId, goodsId, e.getMessage());
            return "{\"error\":\"ADD_TO_CART_FAILED\",\"hint\":\"加入购物车失败，请稍后再试\"}";
        }

        Cart saved = findCart(goodsId);
        if (saved == null) {
            // 写成功却查不到：不谎报成功，让模型提示用户去购物车确认
            return "{\"error\":\"ADD_TO_CART_UNCONFIRMED\",\"hint\":\"已提交加购，请到购物车确认是否成功\"}";
        }
        log.info("工具结果 add_to_cart userId={} goodsId={} cartId={} totalNumber={} unitPrice={}",
                userId, goodsId, saved.getId(), saved.getNumber(), saved.getPrice());
        events.onCartAdded(new CartAdded(saved.getId(), saved.getGoodsId(), saved.getGoodsName(),
                saved.getPrice(), saved.getNumber(), saved.getPicUrl()));
        return toJson(saved);
    }

    /**
     * 查询刚写入的购物车行。按商品维度取最新一行，仅用于回传 cartId 与展示字段。
     *
     * @param goodsId 商品 ID
     * @return 购物车行；查不到时返回 null
     */
    private Cart findCart(Long goodsId) {
        try {
            List<Cart> carts = cartService.list(Wrappers.lambdaQuery(Cart.class)
                    .eq(Cart::getUserId, userId)
                    .eq(Cart::getGoodsId, goodsId)
                    .orderByDesc(Cart::getId));
            return carts == null || carts.isEmpty() ? null : carts.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 加购结果转 JSON。金额用 plainString，避免科学计数法。
     *
     * @param cart 购物车行
     * @return JSON 字符串
     */
    private static String toJson(Cart cart) {
        return "{\"added\":true"
                + ",\"cartId\":" + JsonText.num(cart.getId())
                + ",\"goodsId\":" + JsonText.num(cart.getGoodsId())
                + ",\"goodsName\":" + JsonText.str(cart.getGoodsName())
                + ",\"unitPrice\":" + JsonText.num(cart.getPrice())
                + ",\"number\":" + JsonText.num(cart.getNumber())
                + ",\"hint\":\"已加入购物车；用户说下单时再调用 propose_order\"}";
    }
}
