package com.zeroai.mobile.api.controller.trade;


import com.zeroai.common.base.controller.BaseController;
import com.zeroai.domain.api.trade.service.IMobileOrderService;
import com.zeroai.util.util.R;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 退款接口
 *
 * @since 2024/1/15
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("order/refund")
public class OrderRefundController extends BaseController {

    private IMobileOrderService mobileOrderService;

    /**
     * 申请退款
     *
     * @param orderId 订单id
     * @return R
     */
    @PostMapping("{orderId}")
    public R<Boolean> refund(@PathVariable Long orderId) {
        log.info("申请退款开始, userId={}, orderId={}", com.zeroai.mobile.framework.security.util.MobileSecurityUtils.getUserId(), orderId);
        mobileOrderService.refund(orderId);
        log.info("申请退款完成, userId={}, orderId={}", com.zeroai.mobile.framework.security.util.MobileSecurityUtils.getUserId(), orderId);
        return R.success();
    }

}
