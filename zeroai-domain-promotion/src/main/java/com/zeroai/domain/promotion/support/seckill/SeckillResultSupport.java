package com.zeroai.domain.promotion.support.seckill;

import com.alibaba.fastjson.JSON;
import com.zeroai.data.redis.manager.RedisCache;
import com.zeroai.domain.api.promotion.enums.SeckillResultStatusEnum;
import com.zeroai.domain.api.promotion.response.SeckillResultResVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 秒杀结果缓存支撑服务。
 * 秒杀入口异步返回后，前端通过订单号轮询该缓存；消费端落库成功、失败或关单时统一更新结果。
 */
@Slf4j
@Service
@AllArgsConstructor
public class SeckillResultSupport {

    private static final int RESULT_TTL_SECONDS = 3600;

    private final RedisCache redisCache;

    /**
     * 查询秒杀结果。
     *
     * @param orderSn 订单号
     * @return 秒杀结果
     */
    public SeckillResultResVO getResult(String orderSn) {
        SeckillResultResVO fallback = buildResult(orderSn, SeckillResultStatusEnum.PROCESSING.name(), null, null);
        if (StringUtils.isBlank(orderSn)) {
            return fallback;
        }
        String cached = getCachedResult(orderSn);
        if (StringUtils.isBlank(cached)) {
            return fallback;
        }
        if (cached.trim().startsWith("{")) {
            return JSON.parseObject(cached, SeckillResultResVO.class);
        }
        return buildResult(orderSn, cached, null, null);
    }

    /**
     * 读取秒杀结果缓存。
     * 兼容历史 Lua 写入的裸 PROCESSING 值：裸字符串无法被 FastJSON Redis 序列化器反序列化，查询时按处理中返回。
     *
     * @param orderSn 订单号
     * @return 缓存结果字符串
     */
    private String getCachedResult(String orderSn) {
        try {
            return redisCache.getCacheObject(SeckillRedisKeySupport.resultKey(orderSn));
        } catch (SerializationException e) {
            log.warn("秒杀结果缓存反序列化失败，按处理中返回, orderSn={}, message={}", orderSn, e.getMessage());
            return SeckillResultStatusEnum.PROCESSING.name();
        }
    }

    /**
     * 标记秒杀订单创建成功。
     *
     * @param orderSn 订单号
     * @param actualPrice 实付金额
     */
    public void markSuccess(String orderSn, BigDecimal actualPrice) {
        cacheResult(buildResult(orderSn, SeckillResultStatusEnum.SUCCESS.name(), actualPrice, null));
    }

    /**
     * 标记秒杀订单处理失败。
     *
     * @param orderSn 订单号
     * @param failReason 失败原因
     */
    public void markFailed(String orderSn, String failReason) {
        cacheResult(buildResult(orderSn, SeckillResultStatusEnum.FAILED.name(), null, failReason));
    }

    /**
     * 标记秒杀订单超时关闭。
     *
     * @param orderSn 订单号
     */
    public void markClosed(String orderSn) {
        cacheResult(buildResult(orderSn, SeckillResultStatusEnum.CLOSED.name(), null, "超时未支付，订单已关闭"));
    }

    /**
     * 写入秒杀结果缓存。
     *
     * @param result 秒杀结果
     */
    private void cacheResult(SeckillResultResVO result) {
        if (StringUtils.isBlank(result.getOrderSn())) {
            return;
        }
        redisCache.setCacheObject(SeckillRedisKeySupport.resultKey(result.getOrderSn()),
                JSON.toJSONString(result), RESULT_TTL_SECONDS);
    }

    /**
     * 构建秒杀结果。
     *
     * @param orderSn 订单号
     * @param status 状态
     * @param actualPrice 实付金额
     * @param failReason 失败原因
     * @return 秒杀结果
     */
    private SeckillResultResVO buildResult(String orderSn, String status, BigDecimal actualPrice, String failReason) {
        SeckillResultResVO resVO = new SeckillResultResVO();
        resVO.setOrderSn(orderSn);
        resVO.setStatus(status);
        resVO.setActualPrice(actualPrice);
        resVO.setFailReason(failReason);
        return resVO;
    }
}
