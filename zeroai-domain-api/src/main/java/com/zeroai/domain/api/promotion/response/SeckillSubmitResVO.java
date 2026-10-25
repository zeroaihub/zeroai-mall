package com.zeroai.domain.api.promotion.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 秒杀提交响应。
 */
@Data
public class SeckillSubmitResVO {

    private String orderSn;

    private String status;

    private BigDecimal actualPrice;
}
