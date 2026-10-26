package com.zeroai.simulator.client;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端接口封装。
 * <p>
 * 只封装「发货」这一条压测链路真正需要的接口。
 * <p>
 * 为什么不在这里做后台登录：后台 {@code /login} 要求先取图形验证码并提交识别结果，
 * 自动化识别验证码既不稳定也没必要。压测只需要一个已登录的后台 token，
 * 因此由配置项 {@code sim.account.adminToken} 直接注入，并在 warmup 阶段用 {@code /getInfo} 校验有效性。
 */
public final class AdminApi {

    private final ApiClient http;

    public AdminApi(ApiClient http) {
        this.http = http;
    }

    /**
     * 校验后台 token 是否有效。
     *
     * @param adminToken 后台登录态
     * @return 调用结果
     */
    public ApiResponse checkToken(String adminToken) {
        return http.getAdmin("admin.getInfo", "/getInfo", null, adminToken);
    }

    /**
     * 订单发货。
     * <p>
     * 走 {@code AdminOrderShipmentSupport}，带当前状态条件更新：
     * 只有已支付订单能进入已发货，重复发货会被拦下，所以这里不需要额外幂等控制。
     *
     * @param adminToken  后台登录态
     * @param orderId     订单 ID
     * @param shipChannel 发货渠道，例如 SF
     * @param shipSn      运单号
     * @return 调用结果
     */
    public ApiResponse ship(String adminToken, long orderId, String shipChannel, String shipSn) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("shipChannel", shipChannel);
        body.put("shipSn", shipSn);
        return http.postAdmin("admin.ship", "/shop/order/ship", body, adminToken);
    }
}
