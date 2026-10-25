package com.zeroai.domain.trade.support.seckill;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 秒杀异步落单消息体。
 * 入口线程只携带落库必需字段，消费端重新查询活动 SKU、商品、地址等最新数据并执行 MySQL 条件冻结。
 */
@Data
public class SeckillOrderSubmitMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = -6011296820836392488L;

    private String orderSn;

    private Long userId;

    private Long addressId;

    private Long activityId;

    private Long activitySkuId;

    private Long goodsId;

    private Long productId;

    private Integer number;

    private String message;
}
