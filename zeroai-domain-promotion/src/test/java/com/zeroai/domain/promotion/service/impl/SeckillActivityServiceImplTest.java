package com.zeroai.domain.promotion.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.zeroai.data.redis.manager.RedisCache;
import com.zeroai.domain.api.goods.entity.Goods;
import com.zeroai.domain.api.goods.entity.GoodsProduct;
import com.zeroai.domain.api.goods.service.IGoodsProductService;
import com.zeroai.domain.api.goods.service.IGoodsService;
import com.zeroai.domain.api.promotion.entity.SeckillActivity;
import com.zeroai.domain.api.promotion.entity.SeckillSku;
import com.zeroai.domain.api.promotion.mapper.SeckillActivityMapper;
import com.zeroai.domain.api.promotion.request.SeckillActivitySaveReqVO;
import com.zeroai.domain.api.promotion.response.SeckillSkuResVO;
import com.zeroai.domain.api.promotion.service.ISeckillSkuService;
import com.zeroai.util.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 秒杀活动保存服务测试。
 */
@ExtendWith(MockitoExtension.class)
class SeckillActivityServiceImplTest {

    @Mock
    private ISeckillSkuService seckillSkuService;

    @Mock
    private RedisCache redisCache;

    @Mock
    private IGoodsService goodsService;

    @Mock
    private IGoodsProductService goodsProductService;

    @Mock
    private SeckillActivityMapper seckillActivityMapper;

    /**
     * 同一活动下活动 SKU 以 productId 作为唯一维度，重复货品必须在业务层拦截，避免落到数据库唯一键异常。
     */
    @Test
    void saveActivityRejectsDuplicateProductIdsBeforeWritingDatabase() {
        SeckillActivityServiceImpl service = buildService();
        ReflectionTestUtils.setField(service, "baseMapper", seckillActivityMapper);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.saveActivity(buildRequestWithDuplicateProductId()));

        assertEquals("秒杀活动货品不能重复", exception.getMsg());
        verifyNoInteractions(seckillActivityMapper, seckillSkuService);
    }

    /**
     * 更新活动替换 SKU 时不能使用 remove 逻辑删除，否则历史记录仍会占用 activityId + productId 唯一键。
     */
    @Test
    void updateActivityDoesNotUseLogicalRemoveWhenReplacingSkuList() {
        SeckillActivityServiceImpl service = buildService();
        ReflectionTestUtils.setField(service, "baseMapper", seckillActivityMapper);
        when(seckillActivityMapper.updateById(any(SeckillActivity.class))).thenReturn(1);
        when(seckillSkuService.saveBatch(anyCollection())).thenReturn(true);

        service.updateActivity(buildUpdateRequest());

        verify(seckillSkuService).physicalDeleteByActivityId(1L);
        verify(seckillSkuService, never()).remove(any());
    }

    /**
     * 活动详情需要返回商品图片，移动端秒杀详情页直接使用该字段展示 SKU 图。
     */
    @Test
    void detailReturnsGoodsImageForSku() {
        SeckillActivityServiceImpl service = buildService();
        ReflectionTestUtils.setField(service, "baseMapper", seckillActivityMapper);
        when(seckillActivityMapper.selectById(1L)).thenReturn(buildActivity(1L));
        when(seckillSkuService.list(any(Wrapper.class))).thenReturn(List.of(buildSku(10L, 20L)));
        when(goodsService.selectGoodsByIds(List.of(10L))).thenReturn(List.of(buildGoods(10L, "/goods-main.jpg")));
        when(goodsProductService.selectProductByIds(List.of(20L))).thenReturn(List.of(buildProduct(20L, "/sku-main.jpg")));

        SeckillSkuResVO skuRes = service.detail(1L).getSkuList().get(0);

        assertEquals("秒杀商品", skuRes.getGoodsName());
        assertEquals("/goods-main.jpg", skuRes.getGoodsPicUrl());
        assertEquals("/sku-main.jpg", skuRes.getProductPicUrl());
        assertEquals("/sku-main.jpg", skuRes.getPicUrl());
    }

    /**
     * 构建带商品依赖的秒杀活动服务。
     *
     * @return 秒杀活动服务
     */
    private SeckillActivityServiceImpl buildService() {
        return new SeckillActivityServiceImpl(seckillSkuService, redisCache, goodsService, goodsProductService);
    }

    private SeckillActivitySaveReqVO buildRequestWithDuplicateProductId() {
        SeckillActivitySaveReqVO reqVO = new SeckillActivitySaveReqVO();
        SeckillActivity activity = new SeckillActivity();
        activity.setName("整点秒杀");
        activity.setStartTime(new Date());
        activity.setEndTime(nextDay());
        reqVO.setActivity(activity);
        reqVO.setSkuList(List.of(buildSku(1L, 229L), buildSku(2L, 229L)));
        return reqVO;
    }

    private SeckillActivitySaveReqVO buildUpdateRequest() {
        SeckillActivitySaveReqVO reqVO = buildRequestWithDuplicateProductId();
        reqVO.getActivity().setId(1L);
        reqVO.setSkuList(List.of(buildSku(1L, 229L), buildSku(2L, 230L)));
        return reqVO;
    }

    private SeckillActivity buildActivity(Long activityId) {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(activityId);
        activity.setName("整点秒杀");
        activity.setStartTime(new Date());
        activity.setEndTime(nextDay());
        return activity;
    }

    private SeckillSku buildSku(Long goodsId, Long productId) {
        SeckillSku sku = new SeckillSku();
        sku.setGoodsId(goodsId);
        sku.setProductId(productId);
        sku.setSeckillPrice(new BigDecimal("9.90"));
        sku.setAvailableStock(10);
        sku.setLimitCount(1);
        return sku;
    }

    /**
     * 构建商品主图数据。
     *
     * @param goodsId 商品 ID
     * @param picUrl 商品主图
     * @return 商品信息
     */
    private Goods buildGoods(Long goodsId, String picUrl) {
        Goods goods = new Goods();
        goods.setId(goodsId);
        goods.setName("秒杀商品");
        goods.setPicUrl(picUrl);
        return goods;
    }

    /**
     * 构建货品图片数据。
     *
     * @param productId 货品 ID
     * @param picUrl 货品图片
     * @return 货品信息
     */
    private GoodsProduct buildProduct(Long productId, String picUrl) {
        GoodsProduct product = new GoodsProduct();
        product.setId(productId);
        product.setUrl(picUrl);
        product.setSpecifications(new String[]{"红色", "64G"});
        return product;
    }

    private Date nextDay() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        return calendar.getTime();
    }
}
