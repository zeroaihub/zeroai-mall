package com.zeroai.domain.api.promotion.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀 token 响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillTokenResVO {

    private String token;

    private Integer expireSecond;
}
