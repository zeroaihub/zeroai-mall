package com.zeroai.domain.promotion.support.seckill;

import com.zeroai.data.redis.manager.RedisCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀访问 token 支撑服务测试。
 */
@ExtendWith(MockitoExtension.class)
class SeckillTokenSupportTest {

    @Mock
    private RedisCache redisCache;

    @InjectMocks
    private SeckillTokenSupport seckillTokenSupport;

    /**
     * 生成 token 时必须写入用户和活动 SKU 维度的短期 Redis Key。
     */
    @Test
    void issueTokenStoresTokenWithUserSkuScope() {
        String token = seckillTokenSupport.issueToken(10L, 99L);

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisCache).setCacheObject(eq(SeckillRedisKeySupport.tokenKey(10L, 99L)),
                tokenCaptor.capture(), eq(60));
        assertThat(token).isEqualTo(tokenCaptor.getValue());
        assertThat(token).isNotBlank();
    }

    /**
     * 校验 token 时只接受当前用户当前活动 SKU 对应的 Redis 值。
     */
    @Test
    void validateTokenMatchesRedisToken() {
        when(redisCache.getCacheObject(SeckillRedisKeySupport.tokenKey(10L, 99L))).thenReturn("token-1");

        assertThat(seckillTokenSupport.validateToken(10L, 99L, "token-1")).isTrue();
        assertThat(seckillTokenSupport.validateToken(10L, 99L, "token-2")).isFalse();
    }
}
