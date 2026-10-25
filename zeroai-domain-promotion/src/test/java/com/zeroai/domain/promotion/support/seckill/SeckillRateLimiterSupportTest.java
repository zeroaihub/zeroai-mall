package com.zeroai.domain.promotion.support.seckill;

import com.zeroai.data.redis.manager.RedisCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 秒杀令牌桶限流测试。
 */
@ExtendWith(MockitoExtension.class)
class SeckillRateLimiterSupportTest {

    @Mock
    private RedisCache redisCache;

    @InjectMocks
    private SeckillRateLimiterSupport seckillRateLimiterSupport;

    /**
     * SKU 总入口桶和用户桶都拿到令牌时才允许进入库存 Lua。
     */
    @Test
    void allowSubmitRequiresSkuAndUserBuckets() {
        when(redisCache.luaTokenBucket(SeckillRedisKeySupport.skuBucketKey(10L), 200, 200, 1))
                .thenReturn(1L);
        when(redisCache.luaTokenBucket(SeckillRedisKeySupport.userBucketKey(10L, 99L), 3, 3, 1))
                .thenReturn(1L);

        assertThat(seckillRateLimiterSupport.allowSubmit(10L, 99L)).isTrue();
    }

    /**
     * 任意一层令牌桶拒绝时，当前请求都不能继续打 Redis 库存扣减脚本。
     */
    @Test
    void allowSubmitRejectsWhenAnyBucketEmpty() {
        when(redisCache.luaTokenBucket(SeckillRedisKeySupport.skuBucketKey(10L), 200, 200, 1))
                .thenReturn(1L);
        when(redisCache.luaTokenBucket(SeckillRedisKeySupport.userBucketKey(10L, 99L), 3, 3, 1))
                .thenReturn(0L);

        assertThat(seckillRateLimiterSupport.allowSubmit(10L, 99L)).isFalse();
    }
}
