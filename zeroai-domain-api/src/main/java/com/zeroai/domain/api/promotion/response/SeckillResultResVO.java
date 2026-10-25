package com.zeroai.domain.api.promotion.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 秒杀结果响应。
 */
@Data
public class SeckillResultResVO {

    private String orderSn;

    private String status;

    private BigDecimal actualPrice;

    private String failReason;
}
