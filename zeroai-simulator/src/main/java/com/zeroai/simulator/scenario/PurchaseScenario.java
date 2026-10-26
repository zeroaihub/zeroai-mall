package com.zeroai.simulator.scenario;

import com.zeroai.simulator.auth.VirtualUser;
import com.zeroai.simulator.client.AdminApi;
import com.zeroai.simulator.client.ApiResponse;
import com.zeroai.simulator.client.MobileApi;
import com.zeroai.simulator.config.SimConfig;
import com.zeroai.simulator.metric.Metrics;

import java.util.List;

/**
 * 普通购买全链路：搜索 → 详情 → 加购 → 下单 → 支付 →（可选）发货 → 收货。
 * <p>
 * 刻意保留两个「不等价于失败」的分支：
 * <ul>
 *   <li>加购后放弃：真实用户漏斗里约三成会走开，压测不该把它算成系统错误；</li>
 *   <li>下单不支付：制造未支付订单，才能覆盖 60 秒超时关单与库存回补这条补偿链路。</li>
 * </ul>
 * 这两种情况按「链路正常结束」返回 true，但会单独打点，报告里能区分出来。
 */
public final class PurchaseScenario extends AbstractScenario {

    public PurchaseScenario(SimConfig cfg, MobileApi mobile, AdminApi admin, Metrics metrics) {
        super(cfg, mobile, admin, metrics);
    }

    @Override
    public String name() {
        return "normal_purchase";
    }

    @Override
    public boolean run(VirtualUser user) {
        String keyword = pick(cfg.keywords());        ApiResponse search = mobile.search(user.token(), keyword, 1);
        if (!search.ok()) {
            return false;
        }
        List<Long> goodsIds = MobileApi.extractGoodsIds(search);
        if (goodsIds.isEmpty()) {
            abort("chain.search_empty");
            return false;
        }

        // 配置了 goodsIds 时只用仿真商品下单：搜索请求照打（要它那部分 QPS），
        // 但落单目标固定为 SIM 商品，避免随机命中真实商品、污染 sales 与库存。
        long goodsId = cfg.goodsIds().isEmpty() ? pick(goodsIds) : pick(cfg.goodsIds());
        ApiResponse detail = mobile.goodsDetail(goodsId);
        long productId = MobileApi.extractDefaultProductId(detail);
        if (productId <= 0) {
            abort("chain.no_product");
            return false;
        }

        if (!mobile.cartAdd(user.token(), goodsId, productId, 1).ok()) {
            return false;
        }
        long cartId = MobileApi.findCartId(mobile.cartList(user.token()), goodsId);
        if (cartId <= 0) {
            abort("chain.no_cart");
            return false;
        }

        // 放弃分支：仍清理购物车。牺牲一点真实性换取状态有界——
        // 否则每轮都往同一用户的购物车累加，几轮之后加购会因为超限开始失败，
        // 那时压出来的是我们自己的脏数据，不是系统问题。
        if (random.nextDouble() < cfg.abandonAfterCartRate()) {
            mobile.cartDelete(user.token(), cartId);
            mark("chain.abandon_cart");
            return true;
        }

        ApiResponse submit = mobile.orderSubmit(user.token(), List.of(cartId), user.addressId(), null);
        String orderSn = MobileApi.extractOrderSn(submit);
        if (!submit.ok() || orderSn == null || orderSn.isEmpty()) {
            return false;
        }

        boolean created = pollOrderCreated(user.token(), orderSn);
        if (!created) {
            // 只有建单失败才由自己清购物车：消费端按 cartId 回读购物车组单，
            // 建单成功后它会顺手删掉这些行，我们再删一次只会刷出一条无意义的失败记录。
            mobile.cartDelete(user.token(), cartId);
            abort("chain.order_not_persisted");
            return false;
        }

        if (random.nextDouble() < cfg.leaveUnpaidRate()) {
            mark("chain.unpaid_leave");
            return true;
        }

        if (!payAndVerify(user.token(), orderSn)) {
            return false;
        }

        if (cfg.shipAndConfirm()) {
            return shipAndConfirm(user, orderSn);
        }
        return true;
    }

    /** 记录一个非失败分支的埋点。 */
    private void mark(String step) {
        metrics.record(step, 0, null);
    }
}
