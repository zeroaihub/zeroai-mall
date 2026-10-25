package com.zeroai.domain.api.trade.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zeroai.domain.api.trade.entity.Order;
import com.zeroai.util.enums.OrderStatusEnum;

/**
 * 订单表 服务类
 *
 * @since 2020-08-11
 */
public interface IOrderUnpaidService extends IService<Order> {
    void unpaid(String orderSn, OrderStatusEnum statusAutoCancel);
}
