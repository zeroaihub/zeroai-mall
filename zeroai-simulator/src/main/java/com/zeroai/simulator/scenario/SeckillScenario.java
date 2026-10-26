package com.zeroai.simulator.scenario;

import com.zeroai.simulator.auth.VirtualUser;
import com.zeroai.simulator.client.AdminApi;
import com.zeroai.simulator.client.ApiResponse;
import com.zeroai.simulator.client.MobileApi;
import com.zeroai.simulator.config.SimConfig;
import com.zeroai.simulator.metric.Metrics;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀场景：签发 token → 提交 → 结果轮询 →（成功则）支付。
 * <p>
 * 两个刻意的设计：
 * <ul>
 *   <li><b>不跳过 token</b>：token 与 activitySkuId + userId 绑定、60 秒过期，绕过它就等于测了一条
 *       生产上不存在的路径，真正防刷与 Lua 分支的覆盖率会失真；</li>
 *   <li><b>齐射前预签 token</b>：爆发瞬间才去签发会被这一步稀释，测不出提交端的极限，
 *       所以 prepare 阶段先批量签发，run 阶段只提交。</li>
 * </ul>
 * 「售罄」「超限购」属于预期内的业务性拒绝，记埋点但不计为系统失败。
 */
public final class SeckillScenario extends AbstractScenario {

    private final Map<String, String> tokenCache = new ConcurrentHashMap<>();
    private volatile long resolvedSkuId;

    public SeckillScenario(SimConfig cfg, MobileApi mobile, AdminApi admin, Metrics metrics) {
        super(cfg, mobile, admin, metrics);
    }

    @Override
    public String name() {
        return "seckill";
    }

    @Override
    public void prepare(List<VirtualUser> users) {
        long skuId = resolveSkuId();
        if (skuId <= 0) {
            return;
        }
        // 用少量平台线程预热，避免用上千虚拟线程把 token 签发接口本身打出噪声
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            for (VirtualUser user : users) {
                pool.submit(() -> {
                    ApiResponse resp = mobile.seckillToken(user.token(), skuId);
                    String token = extractToken(resp);
                    if (token != null) {
                        tokenCache.put(user.mobile(), token);
                    }
                });
            }
            pool.shutdown();
            pool.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        metrics.record("seckill.tokenPrepare", 0, null);
    }

    @Override
    public boolean run(VirtualUser user) {
        long skuId = resolveSkuId();
        if (skuId <= 0) {
            abort("chain.seckill_sku_unresolved");
            return false;
        }

        String seckillToken = tokenCache.remove(user.mobile());
        if (seckillToken == null) {
            ApiResponse tr = mobile.seckillToken(user.token(), skuId);
            seckillToken = extractToken(tr);
            if (seckillToken == null) {
                abort("chain.no_seckill_token");
                return false;
            }
        }

        ApiResponse submit = mobile.seckillSubmit(user.token(), skuId, 1, user.addressId(), seckillToken);
        if (!submit.ok()) {
            return false;
        }
        String orderSn = MobileApi.extractOrderSn(submit);
        if (orderSn == null || orderSn.isEmpty()) {
            abort("chain.seckill_no_order_sn");
            return false;
        }

        String status = pollSeckillStatus(user.token(), orderSn);
        if ("SUCCESS".equals(status)) {
            if (!payAndVerify(user.token(), orderSn)) {
                return false;
            }
            if (cfg.shipAndConfirm()) {
                return shipAndConfirm(user, orderSn);
            }
            return true;
        }

        // 售罄 / 超限购 / 关单：预期内的业务结果，不是系统故障
        abort("seckill." + status);
        return true;
    }

    /**
     * 轮询秒杀结果直到离开 PROCESSING。
     *
     * @param token   登录态
     * @param orderSn 订单号
     * @return SUCCESS / FAILED / CLOSED / PROCESSING（超时未决）
     */
    private String pollSeckillStatus(String token, String orderSn) {
        String status = "PROCESSING";
        long begin = System.currentTimeMillis();
        // 与普通下单共用同一套窗口配置：秒杀同样走 MQ 异步落单，窗口必须一致，
        // 否则会出现「普通下单算成功、秒杀算超时」的口径不一致。
        for (int i = 0; i < cfg.orderPollMaxAttempts(); i++) {
            ApiResponse resp = mobile.seckillResultQuiet(token, orderSn);
            if (resp.ok() && resp.dataObject() != null) {
                String s = resp.dataObject().getString("status");
                if (s != null) {
                    status = s;
                }
                if (!"PROCESSING".equals(status)) {
                    break;
                }
            }
            sleep(cfg.orderPollIntervalMs());
        }
        // 只记最终状态：中间轮询是重试语义，逐次记录会把「等待」显示成一堆失败
        metrics.record("seckill.result", System.currentTimeMillis() - begin,
                "SUCCESS".equals(status) ? null : "SECKILL_" + status);
        return status;
    }

    private String extractToken(ApiResponse resp) {
        if (!resp.ok() || resp.dataObject() == null) {
            return null;
        }
        return resp.dataObject().getString("token");
    }

    /**
     * 解析活动 SKU ID：优先用配置，未配置时从活动详情里取第一个 SKU。
     *
     * @return 活动 SKU ID，无法解析时返回 0
     */
    private long resolveSkuId() {
        if (resolvedSkuId > 0) {
            return resolvedSkuId;
        }
        long fromConfig = cfg.seckillActivitySkuId();
        if (fromConfig > 0) {
            resolvedSkuId = fromConfig;
            return resolvedSkuId;
        }
        long activityId = cfg.seckillActivityId();
        if (activityId <= 0) {
            return 0;
        }
        ApiResponse detail = mobile.seckillDetail(activityId);
        if (!detail.ok() || detail.dataObject() == null) {
            return 0;
        }
        var skuList = detail.dataObject().getJSONArray("skuList");
        if (skuList == null || skuList.isEmpty()) {
            return 0;
        }
        Long id = skuList.getJSONObject(0).getLong("id");
        if (id == null) {
            return 0;
        }
        resolvedSkuId = id;
        return resolvedSkuId;
    }
}
