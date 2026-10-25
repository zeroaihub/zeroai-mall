package com.zeroai.domain.trade.support.seckill;

import com.zeroai.data.redis.manager.RedisCache;
import com.zeroai.domain.api.common.ZeroaiConfig;
import com.zeroai.domain.api.promotion.entity.SeckillActivity;
import com.zeroai.domain.api.promotion.entity.SeckillSku;
import com.zeroai.domain.api.promotion.enums.SeckillActivityStatusEnum;
import com.zeroai.domain.api.promotion.enums.SeckillResultStatusEnum;
import com.zeroai.domain.api.promotion.request.SeckillSubmitReqVO;
import com.zeroai.domain.api.promotion.response.SeckillSubmitResVO;
import com.zeroai.domain.api.promotion.service.ISeckillActivityService;
import com.zeroai.domain.api.promotion.service.ISeckillSkuService;
import com.zeroai.domain.api.trade.service.IOrderActivityRelationService;
import com.zeroai.domain.promotion.support.seckill.SeckillRateLimiterSupport;
import com.zeroai.domain.promotion.support.seckill.SeckillRedisKeySupport;
import com.zeroai.util.enums.ReturnCodeEnum;
import com.zeroai.util.exception.BusinessException;
import com.zeroai.util.util.OrderSnGenUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Objects;

/**
 * 秒杀订单提交支撑服务。
 * 负责漏斗过滤和异步落单消息保存，不直接创建订单；真正落库由 MQ 消费端完成，降低秒杀入口同步耗时。
 */
@Slf4j
@Service
@AllArgsConstructor
public class SeckillOrderSubmitSupport {

    private static final long ACQUIRE_SUCCESS = 1L;
    private static final int RESULT_TTL_SECONDS = 3600;

    private final ISeckillSkuService seckillSkuService;
    private final ISeckillActivityService seckillActivityService;
    private final IOrderActivityRelationService orderActivityRelationService;
    private final SeckillRateLimiterSupport seckillRateLimiterSupport;
    private final SeckillOrderMessageSupport seckillOrderMessageSupport;
    private final RedisCache redisCache;
    private final OrderSnGenUtil orderSnGenUtil;

    /**
     * 提交秒杀订单。
     * 入口线程只完成漏斗过滤、Redis 原子抢占和本地消息写入，避免万人并发时同步打满 MySQL。
     *
     * @param reqVO 秒杀提交请求
     * @param userId 当前用户 ID
     * @return 秒杀提交结果
     */
    public SeckillSubmitResVO submit(SeckillSubmitReqVO reqVO, Long userId) {
        validateSubmitRequest(reqVO, userId);
        SeckillSku sku = requireActiveSku(reqVO.getActivitySkuId());
        Integer number = reqVO.getNumber();
        validateLimitCount(sku, number);
        validateUserPurchaseLimit(sku, userId);
        if (!seckillRateLimiterSupport.allowSubmit(reqVO.getActivitySkuId(), userId)) {
            throw new BusinessException(ReturnCodeEnum.ORDER_SUBMIT_ERROR, "请求过于频繁，请稍后重试");
        }

        String orderSn = orderSnGenUtil.generateOrderSn();
        Long acquireResult = acquireRedisStock(reqVO, sku, userId, orderSn, number);
        if (acquireResult == null || acquireResult != ACQUIRE_SUCCESS) {
            return handleAcquireFailure(sku, userId, acquireResult);
        }

        try {
            seckillOrderMessageSupport.saveSubmitMessage(buildSubmitMessage(reqVO, sku, userId, orderSn));
            return buildProcessingResult(orderSn, calculateActualPrice(sku, number));
        } catch (RuntimeException e) {
            // Redis 抢占成功但 outbox 写入失败时必须回滚入口库存，否则会出现没有订单却少库存。
            releaseEntranceStock(sku, userId, orderSn, number);
            log.error("秒杀下单消息保存失败, orderSn={}, activitySkuId={}, userId={}",
                    orderSn, reqVO.getActivitySkuId(), userId, e);
            throw e;
        }
    }

    /**
     * 校验提交请求。
     *
     * @param reqVO 秒杀提交请求
     * @param userId 当前用户 ID
     */
    private void validateSubmitRequest(SeckillSubmitReqVO reqVO, Long userId) {
        if (reqVO == null || userId == null || reqVO.getActivitySkuId() == null || reqVO.getAddressId() == null) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_MISS_ERROR);
        }
        if (reqVO.getNumber() == null || reqVO.getNumber() <= 0) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_ERROR, "秒杀数量必须大于0");
        }
        if (StringUtils.isBlank(reqVO.getSeckillToken())) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_MISS_ERROR, "秒杀 token 不能为空");
        }
    }

    /**
     * 查询并校验活动 SKU。
     *
     * @param activitySkuId 活动 SKU ID
     * @return 活动 SKU
     */
    private SeckillSku requireActiveSku(Long activitySkuId) {
        SeckillSku sku = seckillSkuService.getById(activitySkuId);
        if (sku == null || !Objects.equals(sku.getStatus(), SeckillActivityStatusEnum.PUBLISHED.getStatus())) {
            throw new BusinessException(ReturnCodeEnum.ORDER_SUBMIT_ERROR, "秒杀商品不可抢购");
        }
        SeckillActivity activity = seckillActivityService.getById(sku.getActivityId());
        Date now = new Date();
        if (activity == null || !Objects.equals(activity.getStatus(), SeckillActivityStatusEnum.PUBLISHED.getStatus())
                || activity.getStartTime() == null || activity.getEndTime() == null
                || now.before(activity.getStartTime()) || now.after(activity.getEndTime())) {
            throw new BusinessException(ReturnCodeEnum.ORDER_SUBMIT_ERROR, "秒杀活动未开始或已结束");
        }
        return sku;
    }

    /**
     * 校验单用户限购数量。
     *
     * @param sku 活动 SKU
     * @param number 本次购买数量
     */
    private void validateLimitCount(SeckillSku sku, Integer number) {
        int limitCount = sku.getLimitCount() == null || sku.getLimitCount() <= 0 ? 1 : sku.getLimitCount();
        if (number > limitCount) {
            throw new BusinessException(ReturnCodeEnum.ORDER_SUBMIT_ERROR, "超过秒杀限购数量");
        }
    }

    /**
     * 校验用户活动商品购买限制。
     * Redis 只拦截当前抢占窗口，数据库关联表负责持久化兜底，防止 Redis Key 过期后重复购买同一活动商品。
     *
     * @param sku 活动 SKU
     * @param userId 当前用户 ID
     */
    private void validateUserPurchaseLimit(SeckillSku sku, Long userId) {
        if (orderActivityRelationService.existsActiveSeckillPurchase(sku.getActivityId(), sku.getGoodsId(), userId)) {
            throw new BusinessException(ReturnCodeEnum.ORDER_SUBMIT_ERROR, "请勿重复购买该活动商品");
        }
    }

    /**
     * 使用 Redis Lua 原子抢占秒杀库存。
     *
     * @param reqVO 秒杀提交请求
     * @param sku 活动 SKU
     * @param userId 当前用户 ID
     * @param orderSn 订单号
     * @param number 抢购数量
     * @return Lua 返回码
     */
    private Long acquireRedisStock(SeckillSubmitReqVO reqVO, SeckillSku sku, Long userId, String orderSn, Integer number) {
        return redisCache.luaAcquireSeckillStock(
                SeckillRedisKeySupport.stockKey(sku.getId()),
                SeckillRedisKeySupport.userPurchaseKey(sku.getActivityId(), sku.getGoodsId(), userId),
                SeckillRedisKeySupport.tokenKey(sku.getId(), userId),
                SeckillRedisKeySupport.resultKey(orderSn),
                reqVO.getSeckillToken(),
                orderSn,
                number,
                RESULT_TTL_SECONDS,
                RESULT_TTL_SECONDS);
    }

    /**
     * 处理 Redis 抢占失败。
     *
     * @param sku 活动 SKU
     * @param userId 当前用户 ID
     * @param acquireResult Lua 返回码
     * @return 重复提交时返回已有订单号
     */
    private SeckillSubmitResVO handleAcquireFailure(SeckillSku sku, Long userId, Long acquireResult) {
        if (acquireResult != null && acquireResult == -3L) {
            String existingOrderSn = getExistingOrderSn(sku, userId);
            if (StringUtils.isNotBlank(existingOrderSn)) {
                return buildProcessingResult(existingOrderSn, null);
            }
        }
        String message = switch (acquireResult == null ? 0 : acquireResult.intValue()) {
            case -1 -> "秒杀库存不足";
            case -2 -> "秒杀库存未预热";
            case -3 -> "请勿重复抢购";
            case -4 -> "秒杀 token 已失效";
            default -> "秒杀请求失败";
        };
        throw new BusinessException(ReturnCodeEnum.ORDER_SUBMIT_ERROR, message);
    }

    /**
     * 获取用户已有秒杀订单号。
     * 兼容历史 Lua 重复加引号写出的脏值，反序列化失败时降级为重复抢购业务错误，避免接口返回 500。
     *
     * @param sku 活动 SKU
     * @param userId 当前用户 ID
     * @return 已抢占订单号，无法读取时返回 null
     */
    private String getExistingOrderSn(SeckillSku sku, Long userId) {
        try {
            return redisCache.getCacheObject(SeckillRedisKeySupport.userPurchaseKey(sku.getActivityId(), sku.getGoodsId(), userId));
        } catch (SerializationException e) {
            log.warn("秒杀重复购买标记反序列化失败, activityId={}, goodsId={}, userId={}, message={}",
                    sku.getActivityId(), sku.getGoodsId(), userId, e.getMessage());
            return null;
        }
    }

    /**
     * 构建秒杀异步落单消息。
     *
     * @param reqVO 秒杀提交请求
     * @param sku 活动 SKU
     * @param userId 当前用户 ID
     * @param orderSn 订单号
     * @return 秒杀异步落单消息
     */
    private SeckillOrderSubmitMessage buildSubmitMessage(SeckillSubmitReqVO reqVO, SeckillSku sku, Long userId, String orderSn) {
        SeckillOrderSubmitMessage message = new SeckillOrderSubmitMessage();
        message.setOrderSn(orderSn);
        message.setUserId(userId);
        message.setAddressId(reqVO.getAddressId());
        message.setActivityId(sku.getActivityId());
        message.setActivitySkuId(sku.getId());
        message.setGoodsId(sku.getGoodsId());
        message.setProductId(sku.getProductId());
        message.setNumber(reqVO.getNumber());
        message.setMessage(reqVO.getMessage());
        return message;
    }

    /**
     * 回滚 Redis 入口库存抢占。
     *
     * @param sku 活动 SKU
     * @param userId 当前用户 ID
     * @param orderSn 订单号
     * @param number 回滚数量
     */
    private void releaseEntranceStock(SeckillSku sku, Long userId, String orderSn, Integer number) {
        redisCache.incrementCacheObject(SeckillRedisKeySupport.stockKey(sku.getId()), number);
        redisCache.deleteObject(SeckillRedisKeySupport.userPurchaseKey(sku.getActivityId(), sku.getGoodsId(), userId));
        redisCache.deleteObject(SeckillRedisKeySupport.resultKey(orderSn));
    }

    /**
     * 构建处理中响应。
     *
     * @param orderSn 订单号
     * @param actualPrice 实付金额
     * @return 提交响应
     */
    private SeckillSubmitResVO buildProcessingResult(String orderSn, BigDecimal actualPrice) {
        SeckillSubmitResVO resVO = new SeckillSubmitResVO();
        resVO.setOrderSn(orderSn);
        resVO.setStatus(SeckillResultStatusEnum.PROCESSING.name());
        resVO.setActualPrice(actualPrice);
        return resVO;
    }

    /**
     * 计算秒杀订单应付金额。
     *
     * @param sku 活动 SKU
     * @param number 购买数量
     * @return 应付金额
     */
    private BigDecimal calculateActualPrice(SeckillSku sku, Integer number) {
        BigDecimal goodsPrice = sku.getSeckillPrice().multiply(BigDecimal.valueOf(number));
        BigDecimal freightPrice = goodsPrice.compareTo(ZeroaiConfig.getFreightLimit()) < 0
                ? ZeroaiConfig.getFreightPrice()
                : BigDecimal.ZERO;
        return goodsPrice.add(freightPrice).max(BigDecimal.ZERO);
    }
}
