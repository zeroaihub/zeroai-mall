package com.zeroai.domain.trade.support.seckill;

import com.zeroai.domain.api.trade.entity.OrderActivityRelation;
import com.zeroai.domain.api.trade.service.IOrderActivityRelationService;
import com.zeroai.domain.promotion.support.seckill.SeckillResultSupport;
import com.zeroai.domain.trade.support.order.OrderCancellationSupport;
import com.zeroai.util.enums.OrderStatusEnum;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 秒杀订单超时关闭支撑服务。
 * 只有订单状态确实从待支付切换到自动关闭后，才释放秒杀活动库存，避免支付成功与超时消息并发时误回补。
 */
@Service
@AllArgsConstructor
public class SeckillOrderTimeoutSupport {

    private final IOrderActivityRelationService orderActivityRelationService;
    private final OrderCancellationSupport orderCancellationSupport;
    private final SeckillInventoryReleaseSupport seckillInventoryReleaseSupport;
    private final SeckillResultSupport seckillResultSupport;

    /**
     * 关闭超时未支付秒杀订单。
     *
     * @param orderSn 订单号
     */
    public void closeUnpaidOrder(String orderSn) {
        OrderActivityRelation relation = orderActivityRelationService.getByOrderSn(orderSn);
        if (relation == null) {
            return;
        }
        boolean cancelled = orderCancellationSupport.cancel(orderSn, OrderStatusEnum.STATUS_AUTO_CANCEL);
        if (cancelled) {
            seckillInventoryReleaseSupport.releaseTimeoutStock(relation);
            seckillResultSupport.markClosed(orderSn);
        }
    }
}
