package com.zeroai.domain.api.promotion.request;

import lombok.Data;

/**
 * 秒杀提交请求。
 */
@Data
public class SeckillSubmitReqVO {

    private Long activitySkuId;

    private Integer number;

    private Long addressId;

    private String seckillToken;

    private String message;
}
