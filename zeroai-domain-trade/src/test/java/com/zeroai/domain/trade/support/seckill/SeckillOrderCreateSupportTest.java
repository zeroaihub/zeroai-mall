package com.zeroai.domain.trade.support.seckill;

import com.alibaba.fastjson.JSON;
import com.zeroai.data.redis.manager.RedisCache;
import com.zeroai.domain.api.common.MybatisPlusTableInfoTestHelper;
import com.zeroai.domain.api.goods.service.IGoodsProductService;
import com.zeroai.domain.api.goods.service.IGoodsService;
import com.zeroai.domain.api.promotion.entity.SeckillSku;
import com.zeroai.domain.api.promotion.enums.SeckillActivityStatusEnum;
import com.zeroai.domain.api.promotion.service.ISeckillSkuService;
import com.zeroai.domain.api.trade.entity.Order;
import com.zeroai.domain.api.trade.mapper.OrderMapper;
import com.zeroai.domain.api.trade.service.IAddressService;
import com.zeroai.domain.api.trade.service.IOrderActivityRelationService;
import com.zeroai.domain.api.trade.service.IOrderGoodsService;
import com.zeroai.domain.inventory.support.OrderStockSupport;
import com.zeroai.domain.promotion.support.seckill.SeckillRedisKeySupport;
import com.zeroai.domain.promotion.support.seckill.SeckillResultSupport;
import com.zeroai.util.exception.BusinessException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀订单落库支撑服务测试。
 */
@ExtendWith(MockitoExtension.class)
class SeckillOrderCreateSupportTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private IOrderGoodsService orderGoodsService;

    @Mock
    private IOrderActivityRelationService orderActivityRelationService;

    @Mock
    private IAddressService addressService;

    @Mock
    private IGoodsService goodsService;

    @Mock
    private IGoodsProductService goodsProductService;

    @Mock
    private ISeckillSkuService seckillSkuService;

    @Mock
    private OrderStockSupport orderStockSupport;

    @Mock
    private SeckillOrderMessageSupport seckillOrderMessageSupport;

    @Mock
    private SeckillResultSupport seckillResultSupport;

    @Mock
    private RedisCache redisCache;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private SeckillOrderCreateSupport seckillOrderCreateSupport;

    /**
     * 初始化 MyBatis-Plus 实体元数据，保证 lambdaQuery 可在纯单测中正常构建。
     */
    @BeforeAll
    static void beforeAll() {
        MybatisPlusTableInfoTestHelper.init(Order.class);
    }

    /**
     * 消费端落库事务内必须再次检查活动商品购买记录，防止异常消息绕过入口 Redis 漏斗后重复创建订单。
     */
    @Test
    void createRejectsExistingActivityGoodsPurchaseBeforePersistOrder() {
        SeckillOrderSubmitMessage message = buildSubmitMessage();
        when(seckillSkuService.getById(100L)).thenReturn(buildSku());
        when(orderActivityRelationService.existsActiveSeckillPurchase(1L, 10L, 99L)).thenReturn(true);
        doAnswer(invocation -> invocation.<TransactionCallback<Boolean>>getArgument(0).doInTransaction(null))
                .when(transactionTemplate).execute(any());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> seckillOrderCreateSupport.create(JSON.toJSONString(message)));

        assertEquals("请勿重复购买该活动商品", exception.getMsg());
        verify(redisCache).incrementCacheObject(SeckillRedisKeySupport.stockKey(100L), 1L);
        verify(redisCache).deleteObject(SeckillRedisKeySupport.userPurchaseKey(1L, 10L, 99L));
        verify(redisCache).deleteObject(SeckillRedisKeySupport.resultKey("S202606060003"));
        verify(orderMapper, never()).insert(any(Order.class));
    }

    /**
     * 构建秒杀落单消息。
     *
     * @return 秒杀落单消息
     */
    private SeckillOrderSubmitMessage buildSubmitMessage() {
        SeckillOrderSubmitMessage message = new SeckillOrderSubmitMessage();
        message.setOrderSn("S202606060003");
        message.setUserId(99L);
        message.setAddressId(200L);
        message.setActivityId(1L);
        message.setActivitySkuId(100L);
        message.setGoodsId(10L);
        message.setProductId(20L);
        message.setNumber(1);
        return message;
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
        sku.setStatus(SeckillActivityStatusEnum.PUBLISHED.getStatus());
        return sku;
    }
}
