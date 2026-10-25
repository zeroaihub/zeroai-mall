package com.zeroai.domain.promotion.support.seckill;

import com.zeroai.data.redis.manager.RedisCache;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 秒杀访问 token 支撑服务。
 * token 只用于拦截绕过详情页的直接提交请求，不承载库存语义；库存和限购仍由 Redis Lua 原子判断。
 */
@Service
@AllArgsConstructor
public class SeckillTokenSupport {

    static final int TOKEN_TTL_SECONDS = 60;

    private final RedisCache redisCache;

    /**
     * 签发当前用户当前活动 SKU 的短期秒杀 token。
     *
     * @param activitySkuId 活动 SKU ID
     * @param userId 用户 ID
     * @return 秒杀 token
     */
    public String issueToken(Long activitySkuId, Long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redisCache.setCacheObject(SeckillRedisKeySupport.tokenKey(activitySkuId, userId), token, TOKEN_TTL_SECONDS);
        return token;
    }

    /**
     * 校验提交请求携带的秒杀 token。
     *
     * @param activitySkuId 活动 SKU ID
     * @param userId 用户 ID
     * @param token 请求 token
     * @return true=token 匹配
     */
    public boolean validateToken(Long activitySkuId, Long userId, String token) {
        if (activitySkuId == null || userId == null || StringUtils.isBlank(token)) {
            return false;
        }
        String cachedToken = redisCache.getCacheObject(SeckillRedisKeySupport.tokenKey(activitySkuId, userId));
        return token.equals(cachedToken);
    }
}
