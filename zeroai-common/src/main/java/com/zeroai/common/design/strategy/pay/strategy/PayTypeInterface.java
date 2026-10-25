package com.zeroai.common.design.strategy.pay.strategy;

import com.zeroai.domain.api.trade.request.OrderPayReqVO;
import com.zeroai.domain.api.trade.response.OrderPayResVO;

/**
 * 支付策略接口
 */
public interface PayTypeInterface {

    OrderPayResVO pay(OrderPayReqVO reqVo);

    Integer getType();
}
