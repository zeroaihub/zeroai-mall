package com.zeroai.payment.channel.pay;

import cn.hutool.core.lang.id.NanoId;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zeroai.domain.api.trade.entity.Order;
import com.zeroai.domain.api.trade.entity.OrderGoods;
import com.zeroai.domain.api.goods.service.IGoodsService;
import com.zeroai.domain.api.trade.service.IOrderGoodsService;
import com.zeroai.domain.api.trade.service.IOrderService;
import com.zeroai.domain.api.trade.enums.PayTypeEnum;
import com.zeroai.common.design.strategy.pay.strategy.PayTypeInterface;
import com.zeroai.domain.api.trade.request.OrderPayReqVO;
import com.zeroai.domain.api.trade.response.OrderPayResVO;
import com.zeroai.util.enums.OrderStatusEnum;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

/**
 * 微信JSAPI支付策略
 */
@Slf4j
@Component
@AllArgsConstructor
public class TestPayStrategy implements PayTypeInterface {
    private IOrderService orderService;
    private IOrderGoodsService iOrderGoodsService;
    private IGoodsService iGoodsService;

    @Override
    public OrderPayResVO pay(OrderPayReqVO reqVo) {
        Order order = orderService.getOne(new QueryWrapper<Order>().eq("order_sn", reqVo.getOrderSn()));
        order.setPayId(NanoId.randomNanoId());
        order.setPayTime(LocalDateTime.now());
        order.setOrderStatus(OrderStatusEnum.STATUS_PAY.getStatus());
        order.setUpdateTime(new Date());
        orderService.updateById(order);
        updateVirtualSales(order.getId());
        return new OrderPayResVO();
    }

    private void updateVirtualSales(Long orderId) {
        try {
            List<OrderGoods> orderGoods = iOrderGoodsService.list(Wrappers.lambdaQuery(OrderGoods.class)
                    .eq(OrderGoods::getOrderId, orderId));
            for (OrderGoods orderGood : orderGoods) {
                Long goodsId = orderGood.getGoodsId();
                Integer number = orderGood.getNumber();
                iGoodsService.updateVirtualSales(goodsId, number);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    @Override
    public Integer getType() {
        return PayTypeEnum.TEST.getType();
    }
}
