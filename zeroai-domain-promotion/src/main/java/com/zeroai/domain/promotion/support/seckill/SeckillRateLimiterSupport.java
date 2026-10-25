package com.zeroai.domain.promotion.support.seckill;

import com.zeroai.data.redis.manager.RedisCache;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 秒杀令牌桶限流支撑服务。
 * 先按 SKU 总入口削峰，再按用户维度防刷，只有两层都拿到令牌才允许进入库存扣减 Lua。
 */
@Service
@AllArgsConstructor
public class SeckillRateLimiterSupport {

    static final int SKU_BUCKET_CAPACITY = 200;
    static final int SKU_BUCKET_REFILL_RATE = 200;
    static final int USER_BUCKET_CAPACITY = 3;
    static final int USER_BUCKET_REFILL_RATE = 3;
    static final int REQUEST_PERMIT = 1;
    static final long ALLOW = 1L;

    private final RedisCache redisCache;

    /**
     * 判断秒杀提交请求是否允许继续进入库存扣减。
     *
     * @param activitySkuId 活动 SKU ID
     * @param userId 用户 ID
     * @return true=放行
     */
    public boolean allowSubmit(Long activitySkuId, Long userId) {
        Long skuAllowed = redisCache.luaTokenBucket(SeckillRedisKeySupport.skuBucketKey(activitySkuId),
                SKU_BUCKET_CAPACITY, SKU_BUCKET_REFILL_RATE, REQUEST_PERMIT);
        if (skuAllowed == null || skuAllowed != ALLOW) {
            return false;
        }
        Long userAllowed = redisCache.luaTokenBucket(SeckillRedisKeySupport.userBucketKey(activitySkuId, userId),
                USER_BUCKET_CAPACITY, USER_BUCKET_REFILL_RATE, REQUEST_PERMIT);
        return userAllowed != null && userAllowed == ALLOW;
    }
}
