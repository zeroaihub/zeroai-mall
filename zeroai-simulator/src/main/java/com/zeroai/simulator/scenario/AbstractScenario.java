package com.zeroai.simulator.scenario;

import com.zeroai.simulator.auth.VirtualUser;
import com.zeroai.simulator.client.AdminApi;
import com.zeroai.simulator.client.ApiResponse;
import com.zeroai.simulator.client.MobileApi;
import com.zeroai.simulator.config.SimConfig;
import com.zeroai.simulator.metric.Metrics;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 场景公共能力。
 * <p>
 * 把「轮询判定」「支付并校验状态」「链路中断原因记录」这三件所有场景都会做的事收敛在这里，
 * 避免每个场景各写一套轮询，导致成功率口径不一致。
 */
abstract class AbstractScenario implements Scenario {

    /** 订单状态：已付款，见 OrderStatusEnum。 */
    protected static final int STATUS_PAID = 201;

    protected final SimConfig cfg;
    protected final MobileApi mobile;
    protected final AdminApi admin;
    protected final Metrics metrics;
    protected final ThreadLocalRandom random = ThreadLocalRandom.current();

    protected AbstractScenario(SimConfig cfg, MobileApi mobile, AdminApi admin, Metrics metrics) {
        this.cfg = cfg;
        this.mobile = mobile;
        this.admin = admin;
        this.metrics = metrics;
    }

    /**
     * 轮询异步下单结果。
     * <p>
     * 下单接口只返回「已受理」，真正落库在 message-consumer。不轮询就无法区分
     * 「服务端成功」和「服务端只是收了消息」，这是压测里最容易得出虚假成功率的地方。
     *
     * @param token   登录态
     * @param orderSn 订单号
     * @return true 表示消费端已确认建单成功
     */
    protected boolean pollOrderCreated(String token, String orderSn) {
        // 轮询窗口必须来自配置：本地有 3~4 秒的 local_message 转发延迟，
        // 窗口设短了会把正常的异步延迟判成系统错误（曾经因此把成功率压成 0%）。
        long begin = System.currentTimeMillis();
        boolean created = false;
        for (int i = 0; i < cfg.orderPollMaxAttempts(); i++) {
            ApiResponse resp = mobile.orderSearchResultQuiet(token, orderSn);
            if (resp.ok()) {
                created = true;
                break;
            }
            sleep(cfg.orderPollIntervalMs());
        }
        // 只记一条：耗时是「下单到可见」的端到端延迟，这才是这条异步链路真正的 SLA。
        // 中间每次轮询不单独记，否则一次建单会变成十几次失败调用。
        metrics.record("order.searchResult", System.currentTimeMillis() - begin,
                created ? null : "ORDER_NOT_READY");
        return created;
    }

    /**
     * 模拟支付并校验订单确实进入已付款态。
     * <p>
     * 只用 payType=99 的模拟支付，不碰真实渠道。校验状态是因为支付接口返回成功
     * 不等于状态机真的推进了，尤其并发下容易出现条件更新失败仍返回成功。
     *
     * @param token   登录态
     * @param orderSn 订单号
     * @return true 表示订单已是已付款
     */
    protected boolean payAndVerify(String token, String orderSn) {
        ApiResponse pay = mobile.payPrepay(token, orderSn);
        if (!pay.ok()) {
            return false;
        }
        ApiResponse detail = mobile.orderDetail(token, orderSn);
        if (!detail.ok()) {
            return false;
        }
        return MobileApi.extractOrderStatus(detail) == STATUS_PAID;
    }

    /**
     * 走完「后台发货 → 用户确认收货」，把状态机推到 401 闭环。
     * <p>
     * 只有配置开启且配置了后台 token 时才执行：发货在管理端，跨鉴权域，
     * 默认关闭以免它成为压测瓶颈而不自知。
     *
     * @param user    虚拟用户
     * @param orderSn 订单号
     * @return true 表示已确认收货
     */
    protected boolean shipAndConfirm(VirtualUser user, String orderSn) {
        String adminToken = cfg.adminToken();
        if (adminToken == null || adminToken.isEmpty()) {
            abort("chain.missing_admin_token");
            return false;
        }
        ApiResponse detail = mobile.orderDetail(user.token(), orderSn);
        if (!detail.ok()) {
            return false;
        }
        long orderId = MobileApi.extractOrderId(detail);
        if (orderId == 0) {
            return false;
        }
        ApiResponse ship = admin.ship(adminToken, orderId, "SIM", "SIM" + orderId);
        if (!ship.ok()) {
            return false;
        }
        return mobile.orderConfirm(user.token(), orderId).ok();
    }

    /**
     * 记录链路中断原因。
     * <p>
     * 单独记一个伪步骤，报告里能直接看到「为什么没走完」的分布，
     * 而不是只看到一个笼统的链路成功率。
     *
     * @param reason 中断原因
     */
    protected void abort(String reason) {
        metrics.record("chain.abort", 0, reason);
    }

    protected void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected <T> T pick(java.util.List<T> list) {
        return list.get(random.nextInt(list.size()));
    }
}
