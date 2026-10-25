package com.zeroai.domain.trade.support.seckill;

import com.zeroai.domain.api.trade.entity.OrderActivityRelation;
import com.zeroai.domain.api.trade.service.IOrderActivityRelationService;
import com.zeroai.domain.promotion.support.seckill.SeckillResultSupport;
import com.zeroai.domain.trade.support.order.OrderCancellationSupport;
import com.zeroai.util.enums.OrderStatusEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀订单超时释放测试。
 */
@ExtendWith(MockitoExtension.class)
class SeckillOrderTimeoutSupportTest {

    @Mock
    private IOrderActivityRelationService orderActivityRelationService;

    @Mock
    private OrderCancellationSupport orderCancellationSupport;

    @Mock
    private SeckillInventoryReleaseSupport seckillInventoryReleaseSupport;

    @Mock
    private SeckillResultSupport seckillResultSupport;

    @InjectMocks
    private SeckillOrderTimeoutSupport seckillOrderTimeoutSupport;

    /**
     * 订单仍处于待支付并成功关闭时，必须释放秒杀活动库存和用户抢购资格。
     */
    @Test
    void closeUnpaidOrderReleasesSeckillInventoryWhenCancelSucceeded() {
        OrderActivityRelation relation = new OrderActivityRelation();
        relation.setOrderSn("S001");
        relation.setActivitySkuId(10L);
        relation.setUserId(99L);
        when(orderActivityRelationService.getByOrderSn("S001")).thenReturn(relation);
        when(orderCancellationSupport.cancel("S001", OrderStatusEnum.STATUS_AUTO_CANCEL)).thenReturn(true);

        seckillOrderTimeoutSupport.closeUnpaidOrder("S001");

        verify(seckillInventoryReleaseSupport).releaseTimeoutStock(relation);
        verify(seckillResultSupport).markClosed("S001");
    }

    /**
     * 订单已支付或状态不允许关闭时，不能回补秒杀库存，避免支付成功后被超时消息误释放。
     */
    @Test
    void closeUnpaidOrderSkipsReleaseWhenCancelNotApplied() {
        OrderActivityRelation relation = new OrderActivityRelation();
        relation.setOrderSn("S001");
        when(orderActivityRelationService.getByOrderSn("S001")).thenReturn(relation);
        when(orderCancellationSupport.cancel("S001", OrderStatusEnum.STATUS_AUTO_CANCEL)).thenReturn(false);

        seckillOrderTimeoutSupport.closeUnpaidOrder("S001");

        verify(seckillInventoryReleaseSupport, never()).releaseTimeoutStock(relation);
    }
}
