package com.zeroai.domain.promotion.support.seckill;

import com.zeroai.data.redis.manager.RedisCache;
import com.zeroai.domain.api.promotion.enums.SeckillResultStatusEnum;
import com.zeroai.domain.api.promotion.response.SeckillResultResVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.serializer.SerializationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * 秒杀结果缓存查询测试。
 */
@ExtendWith(MockitoExtension.class)
class SeckillResultSupportTest {

    @Mock
    private RedisCache redisCache;

    /**
     * 兼容历史 Lua 写入的裸 PROCESSING 值，避免 FastJSON 反序列化失败直接打断轮询接口。
     */
    @Test
    void getResultReturnsProcessingWhenLegacyRawStatusCannotDeserialize() {
        when(redisCache.getCacheObject(SeckillRedisKeySupport.resultKey("ORDER-1")))
                .thenThrow(new SerializationException("Could not deserialize: PROCESSING"));

        SeckillResultResVO result = new SeckillResultSupport(redisCache).getResult("ORDER-1");

        assertEquals("ORDER-1", result.getOrderSn());
        assertEquals(SeckillResultStatusEnum.PROCESSING.name(), result.getStatus());
    }
}
