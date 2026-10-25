package com.zeroai.common.design.strategy.refund.strategy;

import com.zeroai.domain.api.trade.request.OrderRefundReqVO;
import com.zeroai.common.model.response.OrderRefundResVO;

/**
 * 退款策略接口
 */
public interface RefundInterface {

    OrderRefundResVO refund(OrderRefundReqVO reqVo);

    Integer getType();
}
