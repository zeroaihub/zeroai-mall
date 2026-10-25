package com.zeroai.mobile.api.controller.callback;

import com.zeroai.domain.trade.support.seckill.SeckillOrderCreateSupport;
import com.zeroai.domain.trade.support.seckill.SeckillOrderTimeoutSupport;
import com.zeroai.util.exception.BusinessException;
import com.zeroai.util.util.R;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀订单 MQ 回调接口。
 * 仅供 message-consumer 内部调用，负责触发异步落单和 60 秒未支付关单。
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("callback/seckill/order")
public class  SeckillOrderCallbackController {

    private final SeckillOrderCreateSupport seckillOrderCreateSupport;
    private final SeckillOrderTimeoutSupport seckillOrderTimeoutSupport;

    /**
     * 秒杀异步落单回调。
     *
     * @param order 秒杀落单消息 JSON
     * @return 回调结果
     */
    @PostMapping("submit")
    public R<Void> submit(String order) {
        try {
            seckillOrderCreateSupport.create(order);
            log.info("秒杀落单回调完成, order={}", order);
            return R.success();
        } catch (Exception e) {
            String errorMsg = e instanceof BusinessException businessException ? businessException.getMsg() : e.getMessage();
            log.error("秒杀落单回调失败, order={}, message={}", order, errorMsg, e);
            return R.error();
        }
    }

    /**
     * 秒杀 60 秒未支付关单回调。
     *
     * @param orderSn 订单号
     * @return 回调结果
     */
    @PostMapping("unpaid")
    public R<Void> unpaid(String orderSn) {
        try {
            seckillOrderTimeoutSupport.closeUnpaidOrder(orderSn);
            log.info("秒杀未支付关单回调完成, orderSn={}", orderSn);
            return R.success();
        } catch (Exception e) {
            log.error("秒杀未支付关单回调失败, orderSn={}, message={}", orderSn, e.getMessage(), e);
            return R.error();
        }
    }
}
