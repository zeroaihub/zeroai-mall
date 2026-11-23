package com.zeroai.ai.mall.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zeroai.ai.mall.event.CartAdded;
import com.zeroai.ai.mall.event.GuideUiEvents;
import com.zeroai.domain.api.cart.entity.Cart;
import com.zeroai.domain.api.cart.service.ICartService;
import com.zeroai.util.exception.BusinessException;
import io.agentscope.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 加购工具：把商品放进当前用户购物车。
 * <p>
 * 这是本模块<b>第一个真正写业务数据的工具</b>，因此边界必须写清楚：
 * <ul>
 *   <li>只写购物车行，<b>不创订单、不冻结库存、不发起支付</b>；下单仍然只能由用户在提议卡片上确认；</li>
 *   <li>userId 构造期注入，模型无法指定他人身份，也不存在「给别人加购」的参数；</li>
 *   <li>货品选择交给购物车域原有的默认货品逻辑（{@code addDefaultGoodsProduct}），
 *       与 H5 商品详情页的「加入购物车」走同一条写入路径与同一把用户维度锁；</li>
 *   <li>数量上限、上架状态、库存校验全部复用购物车域的校验，不在本类重复实现。</li>
 * </ul>
 * 之所以允许模型直接调用而不是先弹确认：加购是<b>可逆</b>的低风险动作（用户随时能删），
 * 而反复确认会把「我要买这个」变成两三次点击；真正需要人工把关的是订单写入，那一环没有被放宽。
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
     * <p>
     * 副作用：写一行 {@code shop_cart}（已存在同商品同货品时累加数量），并下发一次加购事件。
     * 不做幂等键：加购本身就是「再加一件」的语义，重复调用等于数量叠加，这是用户可感知的结果。
     *
     * @param goodsId 商品 ID，必须来自本轮搜索结果或商品详情
     * @param number  数量，缺省 1，上限 99
     * @return JSON 字符串，含 cartId、商品名、单价、数量与下一步提示
     */
    @Tool(name = "add_to_cart",
            description = "把指定商品加入当前登录用户的购物车，返回购物车行信息。"
                    + "goodsId 必须来自本轮 search_products 或 get_product_detail 的返回；"
                    + "number 缺省 1。只加购，不创建订单，下单仍需用户确认。",
            readOnly = false,
            concurrencySafe = false)
    public String addToCart(Long goodsId, Integer number) {
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
            return "{\"error\":\"ADD_TO_CART_REJECTED\",\"hint\":" + ToolJson.str(e.getMsg()) + "}";
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
                + ",\"cartId\":" + ToolJson.num(cart.getId())
                + ",\"goodsId\":" + ToolJson.num(cart.getGoodsId())
                + ",\"goodsName\":" + ToolJson.str(cart.getGoodsName())
                + ",\"unitPrice\":" + ToolJson.num(cart.getPrice())
                + ",\"number\":" + ToolJson.num(cart.getNumber())
                + ",\"hint\":\"已加入购物车；用户说下单时再调用 propose_order\"}";
    }
}
