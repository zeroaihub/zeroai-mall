package com.zeroai.data.redis.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 脚本构建测试。
 */
class RedisCacheTest {

    /**
     * 秒杀 Lua 写入的字符串值后续会通过 GenericFastJsonRedisSerializer 读取，必须写成 JSON 字符串，不能写裸文本。
     */
    @Test
    void seckillAcquireScriptWritesJsonSerializableStringValues() {
        String script = RedisCache.buildLuaAcquireSeckillStockScript();

        assertFalse(script.contains("redis.call('set', resultKey, 'PROCESSING'"));
        assertFalse(script.contains("local serializedOrderSn = '\"' .. orderSn .. '\"'"));
        assertTrue(script.contains("redis.call('set', userKey, orderSn, 'EX', userTtl)"));
        assertTrue(script.contains("redis.call('set', resultKey, '\"PROCESSING\"', 'EX', resultTtl)"));
    }
}
