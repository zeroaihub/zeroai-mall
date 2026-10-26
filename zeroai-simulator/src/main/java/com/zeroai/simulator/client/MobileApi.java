package com.zeroai.simulator.client;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 移动端接口封装。
 * <p>
 * 只做「路径 + 入参」的映射，不做重试和业务判定：
 * 业务判定留在场景层，这样场景可以决定「售罄算业务拒绝还是算失败」，
 * 而不是被客户端悄悄吞掉。
 */
public final class MobileApi {

    /** 支付方式：模拟支付。走 TestPayStrategy，直接把订单置为已支付，不调真实渠道。 */
    public static final int PAY_TYPE_MOCK = 99;

    private final ApiClient http;

    public MobileApi(ApiClient http) {
        this.http = http;
    }

    // ---------------------------------------------------------------- 登录与地址

    /** 发送短信验证码。非 prod 环境不会真的发短信，只是往 Redis 写一条固定码。 */
    public ApiResponse genMobileCode(String mobile) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mobile", mobile);
        return http.post("login.genCode", "/genMobileCode", body, null);
    }

    /**
     * 手机号登录。
     *
     * @param mobile 手机号
     * @param code   验证码，非 prod 环境固定 1234
     * @return data 为 token 字符串
     */
    public ApiResponse login(String mobile, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mobile", mobile);
        body.put("yzm", code);
        return http.post("login.submit", "/login", body, null);
    }

    public ApiResponse addressList(String token) {
        return http.get("address.list", "/address/list", null, token);
    }

    /**
     * 新增收货地址。
     * <p>
     * 路径是 {@code POST /address}（AddressController 的 {@code @PostMapping} 没有子路径），
     * 不是 {@code /address/add}。写错会 404，表现为「登录成功但账号池一个都建不起来」。
     *
     * @param token 登录态
     * @param name  收件人
     * @param tel   手机号
     * @return 调用结果
     */
    public ApiResponse addAddress(String token, String name, String tel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("tel", tel);
        body.put("province", "广东省");
        body.put("city", "深圳市");
        body.put("county", "南山区");
        body.put("addressDetail", "科技园仿真路 1 号");
        body.put("areaCode", "440305");
        body.put("postalCode", "518000");
        body.put("isDefault", true);
        return http.post("address.add", "/address", body, token);
    }

    // ---------------------------------------------------------------- 搜索与商品

    /**
     * 搜索商品。
     *
     * @param token   登录态（该接口内部会取 userId 记录搜索历史）
     * @param keyword 关键词
     * @param pageNum 页码
     * @return data 为商品数组
     */
    public ApiResponse search(String token, String keyword, int pageNum) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("keyword", keyword);
        q.put("pageNum", String.valueOf(pageNum));
        q.put("pageSize", "10");
        return http.get("search.result", "/search/result", q, token);
    }

    public ApiResponse hotKeywords() {
        return http.get("search.hotKeywords", "/search/hotKeywords", null, null);
    }

    /**
     * 商品详情。
     *
     * @param goodsId 商品 ID
     * @return data.productList[0].id 即默认货品 ID
     */
    public ApiResponse goodsDetail(long goodsId) {
        return http.get("goods.detail", "/goods/detail/" + goodsId, null, null);
    }

    // ---------------------------------------------------------------- 购物车

    public ApiResponse cartAdd(String token, long goodsId, long productId, int number) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("goodsId", goodsId);
        body.put("productId", productId);
        body.put("number", number);
        return http.post("cart.add", "/cart", body, token);
    }

    public ApiResponse cartList(String token) {
        return http.get("cart.list", "/cart/list", null, token);
    }

    public ApiResponse cartChecked(String token) {
        return http.post("cart.checked", "/cart/getCheckedGoods", null, token);
    }

    public ApiResponse cartDelete(String token, long cartId) {
        return http.delete("cart.delete", "/cart/" + cartId, token);
    }

    // ---------------------------------------------------------------- 订单

    /**
     * 异步提交订单。返回的 orderSn 只代表「受理」，真正落库要靠 searchResult 轮询。
     *
     * @param token     登录态
     * @param cartIds   购物车 ID 列表
     * @param addressId 地址 ID
     * @param couponId  优惠券 ID，可空
     * @return data.orderSn / data.actualPrice
     */
    public ApiResponse orderSubmit(String token, List<Long> cartIds, long addressId, Long couponId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cartIdArr", cartIds);
        body.put("addressId", addressId);
        body.put("payType", PAY_TYPE_MOCK);
        if (couponId != null) {
            body.put("userCouponId", couponId);
        }
        return http.post("order.submit", "/order/submit", body, token);
    }

    public ApiResponse orderSearchResult(String token, String orderSn) {
        return http.get("order.searchResult", "/order/searchResult/" + orderSn, null, token);
    }

    /**
     * 查询异步下单结果，不写指标。
     * <p>
     * 供轮询使用：轮询期间的「还没建好」是预期状态，不是失败。
     * 由调用方在轮询结束时按最终结果记一条，报表才不会被重试噪声污染。
     *
     * @param token   登录态
     * @param orderSn 订单号
     * @return 调用结果
     */
    public ApiResponse orderSearchResultQuiet(String token, String orderSn) {
        return http.getQuiet("order.searchResult", "/order/searchResult/" + orderSn, null, token);
    }

    public ApiResponse orderDetail(String token, String orderSn) {
        return http.get("order.detail", "/order/detail/" + orderSn, null, token);
    }

    public ApiResponse orderConfirm(String token, long orderId) {
        return http.post("order.confirm", "/order/confirm/" + orderId, null, token);
    }

    // ---------------------------------------------------------------- 支付

    /**
     * 模拟支付。
     * <p>
     * 走 {@code payType=99} 的 TestPayStrategy：只更新订单状态与虚拟销量，
     * 不经过渠道验签、支付流水和回调后置动作，因此这一步的耗时不能作为支付容量结论。
     */
    public ApiResponse payPrepay(String token, String orderSn) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderSn", orderSn);
        body.put("payType", PAY_TYPE_MOCK);
        return http.post("pay.prepay", "/pay/prepay", body, token);
    }

    // ---------------------------------------------------------------- 秒杀

    public ApiResponse seckillList() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("pageNum", "1");
        q.put("pageSize", "10");
        return http.get("seckill.list", "/seckill/list", q, null);
    }

    public ApiResponse seckillDetail(long activityId) {
        return http.get("seckill.detail", "/seckill/detail/" + activityId, null, null);
    }

    /** 签发秒杀 token，有效期 60 秒，且与 activitySkuId + userId 绑定。 */
    public ApiResponse seckillToken(String token, long activitySkuId) {
        return http.get("seckill.token", "/seckill/token/" + activitySkuId, null, token);
    }

    public ApiResponse seckillSubmit(String token, long activitySkuId, int number, long addressId,
                                     String seckillToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activitySkuId", activitySkuId);
        body.put("number", number);
        body.put("addressId", addressId);
        body.put("seckillToken", seckillToken);
        return http.post("seckill.submit", "/seckill/submit", body, token);
    }

    public ApiResponse seckillResult(String token, String orderSn) {
        return http.get("seckill.result", "/seckill/result/" + orderSn, null, token);
    }

    /**
     * 查询秒杀结果，不写指标（供轮询使用，理由同 {@link #orderSearchResultQuiet}）。
     *
     * @param token   登录态
     * @param orderSn 订单号
     * @return 调用结果
     */
    public ApiResponse seckillResultQuiet(String token, String orderSn) {
        return http.getQuiet("seckill.result", "/seckill/result/" + orderSn, null, token);
    }

    // ---------------------------------------------------------------- 解析辅助

    /** 从搜索结果里提取商品 ID 列表。 */
    public static List<Long> extractGoodsIds(ApiResponse resp) {
        List<Long> ids = new ArrayList<>();
        JSONArray arr = resp.dataArray();
        if (arr == null) {
            return ids;
        }
        for (int i = 0; i < arr.size(); i++) {
            JSONObject item = arr.getJSONObject(i);
            if (item != null && item.getLong("id") != null) {
                ids.add(item.getLong("id"));
            }
        }
        return ids;
    }

    /** 从商品详情里提取默认货品 ID（productList 第一项的 id）。 */
    public static long extractDefaultProductId(ApiResponse resp) {
        JSONObject data = resp.dataObject();
        if (data == null) {
            return 0;
        }
        JSONArray products = data.getJSONArray("productList");
        if (products == null || products.isEmpty()) {
            return 0;
        }
        JSONObject first = products.getJSONObject(0);
        Long id = first == null ? null : first.getLong("id");
        return id == null ? 0 : id;
    }

    /** 从购物车列表里找出指定商品对应的购物车行 ID。 */
    public static long findCartId(ApiResponse resp, long goodsId) {
        JSONArray arr = resp.dataArray();
        if (arr == null) {
            return 0;
        }
        for (int i = 0; i < arr.size(); i++) {
            JSONObject item = arr.getJSONObject(i);
            if (item == null) {
                continue;
            }
            Long gid = item.getLong("goodsId");
            if (gid != null && gid == goodsId) {
                Long cartId = item.getLong("id");
                return cartId == null ? 0 : cartId;
            }
        }
        return 0;
    }

    /** 提取订单号。 */
    public static String extractOrderSn(ApiResponse resp) {
        JSONObject data = resp.dataObject();
        return data == null ? null : data.getString("orderSn");
    }

    /** 提取订单 ID。 */
    public static long extractOrderId(ApiResponse resp) {
        JSONObject data = resp.dataObject();
        Long id = data == null ? null : data.getLong("id");
        return id == null ? 0 : id;
    }

    /** 提取订单状态码，101/201/301/401 见 OrderStatusEnum。 */
    public static int extractOrderStatus(ApiResponse resp) {
        JSONObject data = resp.dataObject();
        return data == null ? -1 : data.getIntValue("orderStatus", -1);
    }
}
