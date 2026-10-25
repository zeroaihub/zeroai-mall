package com.zeroai.domain.trade.support.seckill;

import com.zeroai.data.redis.manager.RedisCache;
import com.zeroai.domain.api.common.MybatisPlusTableInfoTestHelper;
import com.zeroai.domain.api.promotion.entity.SeckillSku;
import com.zeroai.domain.api.promotion.service.ISeckillSkuService;
import com.zeroai.domain.api.trade.entity.OrderActivityRelation;
import com.zeroai.domain.api.trade.service.IOrderActivityRelationService;
import com.zeroai.domain.promotion.support.seckill.SeckillRedisKeySupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀库存释放支撑服务测试。
 */
@ExtendWith(MockitoExtension.class)
class SeckillInventoryReleaseSupportTest {

    @Mock
    private ISeckillSkuService seckillSkuService;

    @Mock
    private IOrderActivityRelationService orderActivityRelationService;

    @Mock
    private RedisCache redisCache;

    @InjectMocks
    private SeckillInventoryReleaseSupport seckillInventoryReleaseSupport;

    /**
     * 初始化 MyBatis-Plus 实体元数据，保证 lambdaUpdate 可在纯单测中正常构建。
     */
    @BeforeAll
    static void beforeAll() {
        MybatisPlusTableInfoTestHelper.init(SeckillSku.class, OrderActivityRelation.class);
    }

    /**
     * 数据库活动库存释放成功后，Redis 秒杀库存快照必须按释放数量递增，不能覆盖当前值。
     */
    @Test
    void releaseTimeoutStockIncrementsRedisStockWhenDatabaseReleaseSucceeded() {
        OrderActivityRelation relation = new OrderActivityRelation();
        relation.setId(1L);
        relation.setActivitySkuId(10L);
        relation.setActivityId(1L);
        relation.setGoodsId(30L);
        relation.setProductId(20L);
        relation.setUserId(99L);
        relation.setNumber(2);
        when(orderActivityRelationService.update(any())).thenReturn(true);
        when(seckillSkuService.update(any())).thenReturn(true);

        seckillInventoryReleaseSupport.releaseTimeoutStock(relation);

        verify(redisCache).incrementCacheObject(SeckillRedisKeySupport.stockKey(10L), 2L);
        verify(redisCache).deleteObject(SeckillRedisKeySupport.userPurchaseKey(1L, 30L, 99L));
    }
}
