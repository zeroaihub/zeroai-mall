package com.zeroai.domain.api.promotion.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * @date: 2024/6/6 13:58
 */
@Data
public class ShopCouponGiveUserReqVO implements Serializable {

    /**
     * 用户id
     */
    @NotNull
    private Integer userId;

    /**
     * 优惠卷id
     */
    @NotNull
    private Integer couponId;

}
