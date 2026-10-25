package com.zeroai.payment.channel.refund;

import com.zeroai.common.config.EpayConfig;
import com.zeroai.domain.api.trade.enums.PayTypeEnum;
import com.zeroai.common.design.strategy.refund.strategy.RefundInterface;
import com.zeroai.domain.api.trade.request.OrderRefundReqVO;
import com.zeroai.common.model.response.OrderRefundResVO;
import com.zeroai.payment.channel.epay.Epayapi;
import com.zeroai.payment.channel.epay.EpayRefundRequest;
import com.zeroai.util.util.OrderSnGenUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * 易支付-微信退款策略
 */
@Slf4j
@Component
@AllArgsConstructor
public class EpayWxRefundStrategy implements RefundInterface {

    private Epayapi epayapi;
    private OrderSnGenUtil orderSnGenUtil;
    private EpayConfig epayConfig;

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 2000L, multiplier = 1.5))
    @Override
    public OrderRefundResVO refund(OrderRefundReqVO reqVo) {
        String refundSn = orderSnGenUtil.generateRefundOrderSn();
        EpayRefundRequest request = new EpayRefundRequest(epayConfig.getPid(), epayConfig.getKey(), reqVo.getOrderSn(),
                reqVo.getRefundMoney().toString());
        String refund = epayapi.refund(request);
        return new OrderRefundResVO();
    }

    @Override
    public Integer getType() {
        return PayTypeEnum.EPAY_WECHAT.getType();
    }
}
