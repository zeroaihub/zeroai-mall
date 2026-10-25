package com.zeroai.domain.trade.support.seckill;

import com.zeroai.data.redis.manager.RedisCache;
import com.zeroai.domain.api.common.ZeroaiConfig;
import com.zeroai.domain.api.promotion.entity.SeckillActivity;
import com.zeroai.domain.api.promotion.entity.SeckillSku;
import com.zeroai.domain.api.promotion.enums.SeckillActivityStatusEnum;
import com.zeroai.domain.api.promotion.request.SeckillSubmitReqVO;
import com.zeroai.domain.api.promotion.service.ISeckillActivityService;
import com.zeroai.domain.api.promotion.service.ISeckillSkuService;
import com.zeroai.domain.api.trade.service.IOrderActivityRelationService;
import com.zeroai.domain.promotion.support.seckill.SeckillRateLimiterSupport;
import com.zeroai.domain.promotion.support.seckill.SeckillRedisKeySupport;
import com.zeroai.util.exception.BusinessException;
import com.zeroai.util.util.OrderSnGenUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.serializer.SerializationException;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.refEq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 秒杀订单提交支撑服务测试。
 */
@ExtendWith(MockitoExtension.class)
class SeckillOrderSubmitSupportTest {

    @Mock
    private ISeckillSkuService seckillSkuService;

    @Mock
    private ISeckillActivityService seckillActivityService;

    @Mock
    private SeckillRateLimiterSupport seckillRateLimiterSupport;

    @Mock
    private SeckillOrderMessageSupport seckillOrderMessageSupport;

    @Mock
    private IOrderActivityRelationService orderActivityRelationService;

    @Mock
    private RedisCache redisCache;

    @Mock
    private OrderSnGenUtil orderSnGenUtil;

    @InjectMocks
    private SeckillOrderSubmitSupport seckillOrderSubmitSupport;

    /**
     * 同一活动下同一商品已经存在有效秒杀购买记录时，必须在进入 Redis 抢占前拒绝重复购买。
     */
    @Test
    void submitRejectsExistingActivePurchaseForSameActivityGoods() {
        SeckillSubmitReqVO reqVO = buildSubmitRequest();
        when(seckillSkuService.getById(100L)).thenReturn(buildSku());
        when(seckillActivityService.getById(1L)).thenReturn(buildActivity());
        when(orderActivityRelationService.existsActiveSeckillPurchase(1L, 10L, 99L)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> seckillOrderSubmitSupport.submit(reqVO, 99L));

        assertEquals("请勿重复购买该活动商品", exception.getMsg());
        verifyNoInteractions(seckillRateLimiterSupport, orderSnGenUtil, redisCache, seckillOrderMessageSupport);
    }

    /**
     * 秒杀 Redis 抢占必须使用活动商品维度的用户购买标记，避免活动 SKU 重建后同一用户重复购买同一活动商品。
     */
    @Test
    void submitUsesActivityProductPurchaseKeyWhenAcquireRedisStock() {
        new ZeroaiConfig().setFreightLimit(new BigDecimal("999"));
        new ZeroaiConfig().setFreightPrice(BigDecimal.ZERO);
        SeckillSubmitReqVO reqVO = buildSubmitRequest();
        when(seckillSkuService.getById(100L)).thenReturn(buildSku());
        when(seckillActivityService.getById(1L)).thenReturn(buildActivity());
        when(orderActivityRelationService.existsActiveSeckillPurchase(1L, 10L, 99L)).thenReturn(false);
        when(seckillRateLimiterSupport.allowSubmit(100L, 99L)).thenReturn(true);
        when(orderSnGenUtil.generateOrderSn()).thenReturn("S202606060002");
        when(redisCache.luaAcquireSeckillStock(
                eq(SeckillRedisKeySupport.stockKey(100L)),
                eq(SeckillRedisKeySupport.userPurchaseKey(1L, 10L, 99L)),
                eq(SeckillRedisKeySupport.tokenKey(100L, 99L)),
                eq(SeckillRedisKeySupport.resultKey("S202606060002")),
                eq("TOKEN"), eq("S202606060002"), eq(1), eq(3600), eq(3600))).thenReturn(1L);

        seckillOrderSubmitSupport.submit(reqVO, 99L);

        SeckillOrderSubmitMessage expected = new SeckillOrderSubmitMessage();
        expected.setOrderSn("S202606060002");
        expected.setUserId(99L);
        expected.setAddressId(200L);
        expected.setActivityId(1L);
        expected.setActivitySkuId(100L);
        expected.setGoodsId(10L);
        expected.setProductId(20L);
        expected.setNumber(1);
        verify(seckillOrderMessageSupport).saveSubmitMessage(refEq(expected));
    }

    /**
     * 兼容历史 Lua 写出的双引号脏值，重复抢购标记反序列化失败时也不能向接口层抛 500。
     */
    @Test
    void submitReturnsBusinessErrorWhenExistingUserMarkCannotDeserialize() {
        SeckillSubmitReqVO reqVO = buildSubmitRequest();
        when(seckillSkuService.getById(100L)).thenReturn(buildSku());
        when(seckillActivityService.getById(1L)).thenReturn(buildActivity());
        when(seckillRateLimiterSupport.allowSubmit(100L, 99L)).thenReturn(true);
        when(orderSnGenUtil.generateOrderSn()).thenReturn("S202606060001");
        when(redisCache.luaAcquireSeckillStock(anyString(), anyString(), anyString(), anyString(),
                eq("TOKEN"), eq("S202606060001"), eq(1), anyInt(), anyInt())).thenReturn(-3L);
        when(redisCache.getCacheObject(SeckillRedisKeySupport.userPurchaseKey(1L, 10L, 99L)))
                .thenThrow(new SerializationException("bad redis value"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> seckillOrderSubmitSupport.submit(reqVO, 99L));

        assertEquals("请勿重复抢购", exception.getMsg());
    }

    /**
     * 构建合法秒杀提交请求。
     *
     * @return 秒杀提交请求
     */
    private SeckillSubmitReqVO buildSubmitRequest() {
        SeckillSubmitReqVO reqVO = new SeckillSubmitReqVO();
        reqVO.setActivitySkuId(100L);
        reqVO.setAddressId(200L);
        reqVO.setNumber(1);
        reqVO.setSeckillToken("TOKEN");
        return reqVO;
    }

    /**
     * 构建已发布活动 SKU。
     *
     * @return 活动 SKU
     */
    private SeckillSku buildSku() {
        SeckillSku sku = new SeckillSku();
        sku.setId(100L);
        sku.setActivityId(1L);
        sku.setGoodsId(10L);
        sku.setProductId(20L);
        sku.setSeckillPrice(new BigDecimal("9.90"));
        sku.setLimitCount(1);
        sku.setStatus(SeckillActivityStatusEnum.PUBLISHED.getStatus());
        return sku;
    }

    /**
     * 构建当前有效的已发布活动。
     *
     * @return 秒杀活动
     */
    private SeckillActivity buildActivity() {
        long now = System.currentTimeMillis();
        SeckillActivity activity = new SeckillActivity();
        activity.setId(1L);
        activity.setStatus(SeckillActivityStatusEnum.PUBLISHED.getStatus());
        activity.setStartTime(new Date(now - 1000));
        activity.setEndTime(new Date(now + 60_000));
        return activity;
    }
}
