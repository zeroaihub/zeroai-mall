package com.zeroai.domain.promotion.support.seckill;

import com.zeroai.data.redis.constant.CacheConstants;

/**
 * 秒杀 Redis Key 生成支撑类。
 * 统一收敛 token、令牌桶、用户抢购标记和结果 Key，避免秒杀漏斗各层散落硬编码字符串。
 */
public final class SeckillRedisKeySupport {

    private static final String SECKILL_PREFIX = CacheConstants.CACHE_PREFIX + "seckill:";

    /**
     * 工具类禁止实例化。
     */
    private SeckillRedisKeySupport() {
    }

    /**
     * 生成秒杀访问 token Key。
     *
     * @param activitySkuId 活动 SKU ID
     * @param userId 用户 ID
     * @return Redis Key
     */
    public static String tokenKey(Long activitySkuId, Long userId) {
        return SECKILL_PREFIX + "token:" + activitySkuId + ":" + userId;
    }

    /**
     * 生成 SKU 总入口令牌桶 Key。
     *
     * @param activitySkuId 活动 SKU ID
     * @return Redis Key
     */
    public static String skuBucketKey(Long activitySkuId) {
        return SECKILL_PREFIX + "bucket:sku:" + activitySkuId;
    }

    /**
     * 生成用户维度令牌桶 Key。
     *
     * @param activitySkuId 活动 SKU ID
     * @param userId 用户 ID
     * @return Redis Key
     */
    public static String userBucketKey(Long activitySkuId, Long userId) {
        return SECKILL_PREFIX + "bucket:user:" + activitySkuId + ":" + userId;
    }

    /**
     * 生成秒杀活动库存 Key。
     *
     * @param activitySkuId 活动 SKU ID
     * @return Redis Key
     */
    public static String stockKey(Long activitySkuId) {
        return SECKILL_PREFIX + "stock:" + activitySkuId;
    }

    /**
     * 生成用户抢购成功标记 Key。
     *
     * @param activitySkuId 活动 SKU ID
     * @param userId 用户 ID
     * @return Redis Key
     */
    public static String userSuccessKey(Long activitySkuId, Long userId) {
        return SECKILL_PREFIX + "user:" + activitySkuId + ":" + userId;
    }

    /**
     * 生成用户活动商品抢购标记 Key。
     * activitySkuId 可能在后台重建活动商品时变化，活动级重复购买必须按 activityId + goodsId + userId 收敛。
     *
     * @param activityId 活动 ID
     * @param goodsId 商品 ID
     * @param userId 用户 ID
     * @return Redis Key
     */
    public static String userPurchaseKey(Long activityId, Long goodsId, Long userId) {
        return SECKILL_PREFIX + "purchase:" + activityId + ":" + goodsId + ":" + userId;
    }

    /**
     * 生成秒杀下单结果 Key。
     *
     * @param orderSn 订单号
     * @return Redis Key
     */
    public static String resultKey(String orderSn) {
        return SECKILL_PREFIX + "result:" + orderSn;
    }
}
