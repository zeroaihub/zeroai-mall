package com.zeroai.data.redis.manager;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * spring redis 工具类
 **/
@Slf4j
@SuppressWarnings(value = {"unchecked", "rawtypes"})
@Component
@AllArgsConstructor
public class RedisCache {
    public RedisTemplate redisTemplate;

    /**
     * lua原子递增脚本
     */
    public static String buildLuaIncrKeyScript() {
        return """
                local key = KEYS[1]
                local limit = ARGV[1]
                local c = redis.call('get', key)
                if c and tonumber(c) > tonumber(limit) then
                    redis.call('set', key, 0)
                    return c
                end
                return redis.call('incr', key)
                """;
    }

    /**
     * 构建 Redis 库存预占脚本。
     * availableKey 保存热点 SKU 可售库存快照，reservedKey 保存当前正在进入 MySQL 条件冻结的并发预占量，orderKey 防止同一订单重复预占。
     *
     * @return Redis Lua 脚本
     */
    public static String buildLuaReserveStockScript() {
        return """
                local availableKey = KEYS[1]
                local reservedKey = KEYS[2]
                local orderKey = KEYS[3]
                local requestNumber = tonumber(ARGV[1])
                local ttlSeconds = tonumber(ARGV[2])
                if requestNumber == nil or requestNumber <= 0 then
                    return -3
                end
                local available = redis.call('get', availableKey)
                if not available then
                    return -2
                end
                if redis.call('exists', orderKey) == 1 then
                    return 1
                end
                local reserved = redis.call('get', reservedKey)
                if not reserved then
                    reserved = 0
                end
                if tonumber(available) - tonumber(reserved) < requestNumber then
                    return -1
                end
                redis.call('incrby', reservedKey, requestNumber)
                redis.call('set', orderKey, requestNumber, 'EX', ttlSeconds)
                redis.call('expire', reservedKey, ttlSeconds)
                return 1
                """;
    }

    /**
     * 构建 Redis 库存预占释放脚本。
     * 下单线程进入 MySQL 条件冻结后，无论成功还是失败都释放 in-flight 预占，避免 Redis 并发闸门长期占用。
     *
     * @return Redis Lua 脚本
     */
    public static String buildLuaReleaseReservedStockScript() {
        return """
                local reservedKey = KEYS[1]
                local orderKey = KEYS[2]
                local reservedNumber = redis.call('get', orderKey)
                if not reservedNumber then
                    return 0
                end
                local afterRelease = redis.call('decrby', reservedKey, tonumber(reservedNumber))
                if afterRelease < 0 then
                    redis.call('set', reservedKey, 0)
                end
                redis.call('del', orderKey)
                return 1
                """;
    }

    /**
     * 构建 Redis 令牌桶脚本。
     * 使用 Redis 服务端时间计算补充量，避免多实例机器时间漂移导致限流额度不一致。
     *
     * @return Redis Lua 脚本
     */
    public static String buildLuaTokenBucketScript() {
        return """
                local key = KEYS[1]
                local capacity = tonumber(ARGV[1])
                local refillRate = tonumber(ARGV[2])
                local permits = tonumber(ARGV[3])
                if capacity == nil or capacity <= 0 or refillRate == nil or refillRate <= 0 or permits == nil or permits <= 0 then
                    return -1
                end
                local now = redis.call('time')
                local nowMillis = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
                local bucket = redis.call('hmget', key, 'tokens', 'last_refill_time')
                local tokens = tonumber(bucket[1])
                local lastRefillTime = tonumber(bucket[2])
                if tokens == nil then
                    tokens = capacity
                end
                if lastRefillTime == nil then
                    lastRefillTime = nowMillis
                end
                local deltaMillis = math.max(0, nowMillis - lastRefillTime)
                local refillTokens = math.floor(deltaMillis * refillRate / 1000)
                if refillTokens > 0 then
                    tokens = math.min(capacity, tokens + refillTokens)
                    lastRefillTime = nowMillis
                end
                if tokens < permits then
                    redis.call('hmset', key, 'tokens', tokens, 'last_refill_time', lastRefillTime)
                    redis.call('pexpire', key, math.max(1000, math.ceil(capacity * 1000 / refillRate) * 2))
                    return 0
                end
                tokens = tokens - permits
                redis.call('hmset', key, 'tokens', tokens, 'last_refill_time', lastRefillTime)
                redis.call('pexpire', key, math.max(1000, math.ceil(capacity * 1000 / refillRate) * 2))
                return 1
                """;
    }

    /**
     * 构建秒杀库存扣减脚本。
     * 在单个 Lua 中完成 token 校验、用户幂等、库存扣减和结果占位，保证入口漏斗原子性。
     *
     * @return Redis Lua 脚本
     */
    public static String buildLuaAcquireSeckillStockScript() {
        return """
                local stockKey = KEYS[1]
                local userKey = KEYS[2]
                local tokenKey = KEYS[3]
                local resultKey = KEYS[4]
                local requestToken = ARGV[1]
                local orderSn = ARGV[2]
                local number = tonumber(ARGV[3])
                local userTtl = tonumber(ARGV[4])
                local resultTtl = tonumber(ARGV[5])
                if requestToken == nil or requestToken == '' or orderSn == nil or orderSn == '' or number == nil or number <= 0 then
                    return -6
                end
                local cachedToken = redis.call('get', tokenKey)
                if not cachedToken or cachedToken ~= requestToken then
                    return -4
                end
                if redis.call('exists', userKey) == 1 then
                    return -3
                end
                local stock = redis.call('get', stockKey)
                if not stock then
                    return -2
                end
                if tonumber(stock) < number then
                    return -1
                end
                -- orderSn 是 RedisTemplate 序列化后的 ARGV，不能再次手工加引号；resultKey 常量必须写成 JSON 字符串。
                redis.call('decrby', stockKey, number)
                redis.call('set', userKey, orderSn, 'EX', userTtl)
                redis.call('set', resultKey, '"PROCESSING"', 'EX', resultTtl)
                return 1
                """;
    }

    /**
     * 缓存基本的对象，Integer、String、实体类等
     *
     * @param key   缓存的键值
     * @param value 缓存的值
     */
    public <T> void setCacheObject(final String key, final T value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 缓存基本的对象，Integer、String、实体类等
     *
     * @param key      缓存的键值
     * @param value    缓存的值
     * @param timeout  时间
     * @param timeUnit 时间颗粒度
     */
    public <T> void setCacheObject(final String key, final T value, final Integer timeout, final TimeUnit timeUnit) {
        redisTemplate.opsForValue().set(key, value, timeout, timeUnit);
    }

    public <T> void setCacheObject(final String key, final T value, final Integer timeout) {
        redisTemplate.opsForValue().set(key, value, timeout, TimeUnit.SECONDS);
    }

    /**
     * 当 key 不存在时写入缓存对象。
     * 用于业务幂等和短时去重场景，依赖 Redis SET NX EX 保证并发下只有一个线程写入成功。
     *
     * @param key 缓存键值
     * @param value 缓存值
     * @param timeout 超时时间，单位秒
     * @param <T> 缓存值类型
     * @return true=写入成功；false=key 已存在或 Redis 返回失败
     */
    public <T> boolean setCacheObjectIfAbsent(final String key, final T value, final Integer timeout) {
        Boolean result = redisTemplate.opsForValue().setIfAbsent(key, value, timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    /**
     * 设置有效时间
     *
     * @param key     Redis键
     * @param timeout 超时时间
     * @return true=设置成功；false=设置失败
     */
    public boolean expire(final String key, final long timeout) {
        return expire(key, timeout, TimeUnit.SECONDS);
    }

    /**
     * 判断缓存是否存在。
     *
     * @param key 缓存键值
     * @return true=存在；false=不存在
     */
    public boolean existsKey(String key) {
        return redisTemplate.hasKey(key);
    }

    /**
     * 设置有效时间
     *
     * @param key     Redis键
     * @param timeout 超时时间
     * @param unit    时间单位
     * @return true=设置成功；false=设置失败
     */
    public boolean expire(final String key, final long timeout, final TimeUnit unit) {
        return redisTemplate.expire(key, timeout, unit);
    }

    /**
     * 获得缓存的基本对象。
     *
     * @param key 缓存键值
     * @return 缓存键值对应的数据
     */
    public <T> T getCacheObject(final String key) {
        ValueOperations<String, T> operation = redisTemplate.opsForValue();
        return operation.get(key);
    }

    /**
     * 按指定步长递增字符串数值缓存。
     * 用于库存快照这类计数器回补场景，依赖 Redis INCRBY 保证多实例并发释放时不会互相覆盖。
     *
     * @param key 缓存键值
     * @param delta 递增步长
     * @return 递增后的值，Redis 未返回时返回 0
     */
    public Long incrementCacheObject(final String key, long delta) {
        Long result = redisTemplate.opsForValue().increment(key, delta);
        return result == null ? 0L : result;
    }

    /**
     * 获取key剩余过期时间
     * 在 Redis 2.6 或更早版本中，如果键不存在或者键存在但没有关联的过期时间，则命令返回 -1。 <br>
     * 从 Redis 2.8 开始，发生错误时的返回值发生了变化：<br>
     * - 如果该键不存在，该命令将返回 -2。<br>
     * - 如果密钥存在但没有关联的过期时间，则该命令返回 -1。<br>
     * @param key redis key
     * @return long
     */
    public <T> Long ttl(final String key) {
        ValueOperations<String, T> operation = redisTemplate.opsForValue();
        return operation.getOperations().getExpire(key);
    }

    /**
     * 获取多个key的
     *
     * @param keys 多个key组成的集合
     * @return 多个key对应的value
     */
    public <T> List<T> mGetCacheObject(Collection<String> keys) {
        ValueOperations<String, T> operation = redisTemplate.opsForValue();
        return operation.multiGet(keys);
    }

    /**
     * 删除单个对象
     *
     * @param key
     */
    public boolean deleteObject(final String key) {
        return redisTemplate.delete(key);
    }

    /**
     * 删除集合对象
     *
     * @param collection 多个对象
     * @return
     */
    public long deleteObject(final Collection collection) {
        return redisTemplate.delete(collection);
    }

    /**
     * 缓存List数据
     *
     * @param key      缓存的键值
     * @param dataList 待缓存的List数据
     * @return 缓存的对象
     */
    public <T> long setCacheList(final String key, final List<T> dataList) {
        Long count = redisTemplate.opsForList().rightPushAll(key, dataList);
        return count == null ? 0 : count;
    }

    /**
     * 获得缓存的list对象
     *
     * @param key 缓存的键值
     * @return 缓存键值对应的数据
     */
    public <T> List<T> getCacheList(final String key) {
        return redisTemplate.opsForList().range(key, 0, -1);
    }

    /**
     * 缓存Set
     *
     * @param key     缓存键值
     * @param dataSet 缓存的数据
     * @return 缓存数据的对象
     */
    public <T> long setCacheSet(final String key, final Set<T> dataSet) {
        Long count = redisTemplate.opsForSet().add(key, dataSet);
        return count == null ? 0 : count;
    }

    /**
     * 获得缓存的set
     *
     * @param key
     * @return
     */
    public <T> Set<T> getCacheSet(final String key) {
        return redisTemplate.opsForSet().members(key);
    }

    /**
     * 往 Set 中新增一个元素。
     *
     * @param key 缓存键
     * @param value 元素值
     * @param <T> 元素类型
     * @return 新增条数
     */
    public <T> long addCacheSetValue(final String key, final T value) {
        Long count = redisTemplate.opsForSet().add(key, value);
        return count == null ? 0 : count;
    }

    /**
     * 从 Set 中删除一个元素。
     *
     * @param key 缓存键
     * @param value 元素值
     * @param <T> 元素类型
     * @return 删除条数
     */
    public <T> long removeCacheSetValue(final String key, final T value) {
        Long count = redisTemplate.opsForSet().remove(key, value);
        return count == null ? 0 : count;
    }

    /**
     * 缓存Map
     *
     * @param key
     * @param dataMap
     */
    public <T> void setCacheMap(final String key, final Map<String, T> dataMap) {
        if (dataMap != null) {
            redisTemplate.opsForHash().putAll(key, dataMap);
        }
    }

    /**
     * 获得缓存的Map
     *
     * @param key
     * @return
     */
    public <T> Map<String, T> getCacheMap(final String key) {
        return redisTemplate.opsForHash().entries(key);
    }

    /**
     * 往Hash中存入数据
     *
     * @param key   Redis键
     * @param hKey  Hash键
     * @param value 值
     */
    public <T> void setCacheMapValue(final String key, final String hKey, final T value) {
        redisTemplate.opsForHash().put(key, hKey, value);
    }

    public <T> void delCacheMapValue(final String key, final String hKey) {
        redisTemplate.opsForHash().delete(key, hKey);
    }

    public long incrByCacheMapValue(final String key, final String hKey, long value, Integer timeout) {
        Long increment = redisTemplate.opsForHash().increment(key, hKey, value);
        redisTemplate.expire(key, timeout, TimeUnit.SECONDS);
        return increment;
    }

    /**
     * 获取Hash中的数据
     *
     * @param key  Redis键
     * @param hKey Hash键
     * @return Hash中的对象
     */
    public <T> T getCacheMapValue(final String key, final String hKey) {
        HashOperations<String, String, T> opsForHash = redisTemplate.opsForHash();
        return opsForHash.get(key, hKey);
    }

    /**
     * 获取多个Hash中的数据
     *
     * @param key   Redis键
     * @param hKeys Hash键集合
     * @return Hash对象集合
     */
    public <T> List<T> getMultiCacheMapValue(final String key, final Collection<Object> hKeys) {
        return redisTemplate.opsForHash().multiGet(key, hKeys);
    }

    /**
     * 获得缓存的基本对象列表
     *
     * @param pattern 字符串前缀
     * @return 对象列表
     */
    public Collection<String> keys(final String pattern) {
        return redisTemplate.keys(pattern);
    }

    /**
     * 缓存zset
     *
     * @param key   缓存键名
     * @param value 缓存键值
     * @param score 分数
     * @return 缓存数据的对象
     */
    public <T> ZSetOperations<String, T> setCacheZset(String key, T value, double score) {
        ZSetOperations operations = redisTemplate.opsForZSet();
        operations.add(key, value, score);
        return operations;
    }

    /**
     * 删除zset
     *
     * @param key   缓存键名
     * @param value 缓存键值
     * @return 删除个数
     */
    public <T> Long deleteZsetObject(String key, T value) {
        ZSetOperations operations = redisTemplate.opsForZSet();
        return operations.remove(key, value);
    }

    /**
     * 获得缓存的set
     *
     * @param key 缓存键名
     * @param min 最低分数
     * @param max 最高分数
     * @return 满足分数区间的键值
     */
    public <T> Set<T> getCacheZset(String key, double min, double max) {
        ZSetOperations operations = redisTemplate.opsForZSet();
        return operations.rangeByScore(key, min, max);
    }

    /**
     * @param key
     * @param orderSnIncrLimit
     * @return
     */
    public Long luaIncrKey(String key, Integer orderSnIncrLimit) {
        RedisScript<Long> redisScript = new DefaultRedisScript<>(buildLuaIncrKeyScript(), Long.class);
        return (Long) redisTemplate.execute(redisScript, Collections.singletonList(key), orderSnIncrLimit);
    }

    /**
     * 使用 Lua 原子预占热点 SKU 库存并发闸门。
     *
     * @param availableKey Redis 可售库存快照 Key
     * @param reservedKey SKU 维度正在进入 MySQL 冻结链路的预占量 Key
     * @param orderKey 订单 SKU 维度预占幂等 Key
     * @param number 预占数量
     * @param ttlSeconds 预占过期秒数
     * @return 1=预占成功，-1=Redis 快照库存不足，-2=未初始化可售库存，-3=参数非法
     */
    public Long luaReserveStock(String availableKey, String reservedKey, String orderKey, Integer number,
                                Integer ttlSeconds) {
        RedisScript<Long> redisScript = new DefaultRedisScript<>(buildLuaReserveStockScript(), Long.class);
        return (Long) redisTemplate.execute(redisScript, List.of(availableKey, reservedKey, orderKey), number, ttlSeconds);
    }

    /**
     * 使用 Lua 原子释放热点 SKU 库存预占。
     *
     * @param reservedKey SKU 维度预占量 Key
     * @param orderKey 订单 SKU 维度预占幂等 Key
     * @param number 释放数量，脚本优先使用 orderKey 内保存的数量
     * @return 1=释放成功，0=预占不存在
     */
    public Long luaReleaseReservedStock(String reservedKey, String orderKey) {
        RedisScript<Long> redisScript = new DefaultRedisScript<>(buildLuaReleaseReservedStockScript(), Long.class);
        return (Long) redisTemplate.execute(redisScript, List.of(reservedKey, orderKey));
    }

    /**
     * 使用 Redis Lua 执行分布式令牌桶限流。
     *
     * @param key 令牌桶 Key
     * @param capacity 桶容量
     * @param refillRate 每秒补充令牌数
     * @param permits 本次请求消耗令牌数
     * @return 1=放行，0=令牌不足，-1=参数非法
     */
    public Long luaTokenBucket(String key, Integer capacity, Integer refillRate, Integer permits) {
        RedisScript<Long> redisScript = new DefaultRedisScript<>(buildLuaTokenBucketScript(), Long.class);
        return (Long) redisTemplate.execute(redisScript, List.of(key), capacity, refillRate, permits);
    }

    /**
     * 使用 Redis Lua 执行秒杀库存抢占。
     *
     * @param stockKey 秒杀库存 Key
     * @param userKey 用户抢购标记 Key
     * @param tokenKey 秒杀访问 token Key
     * @param resultKey 秒杀结果 Key
     * @param requestToken 请求 token
     * @param orderSn 订单号
     * @param number 抢购数量
     * @param userTtl 用户抢购标记过期秒数
     * @param resultTtl 结果过期秒数
     * @return 1=成功，-1=库存不足，-2=库存未初始化，-3=重复抢购，-4=token非法，-6=参数非法
     */
    public Long luaAcquireSeckillStock(String stockKey, String userKey, String tokenKey, String resultKey,
                                       String requestToken, String orderSn, Integer number,
                                       Integer userTtl, Integer resultTtl) {
        RedisScript<Long> redisScript = new DefaultRedisScript<>(buildLuaAcquireSeckillStockScript(), Long.class);
        return (Long) redisTemplate.execute(redisScript, List.of(stockKey, userKey, tokenKey, resultKey),
                requestToken, orderSn, number, userTtl, resultTtl);
    }

    /**
     * 获取匹配的所有key，使用scan避免阻塞
     *
     * @param pattern 匹配keys的规则
     * @return 返回获取到的keys
     */
    public Set<String> scan(String pattern) {
        return (Set<String>) redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> keysTmp = new HashSet<>();
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(ScanOptions.scanOptions()
                    .match(pattern)
                    .count(1000).build())) {
                while (cursor.hasNext()) {
                    keysTmp.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                throw new RuntimeException(e);
            }
            return keysTmp;
        });
    }

}
