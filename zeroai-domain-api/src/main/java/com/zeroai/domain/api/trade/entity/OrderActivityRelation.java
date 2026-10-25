package com.zeroai.domain.api.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 订单活动关联表。
 * 按订单商品粒度记录普通订单明细与秒杀活动 SKU 的关系，订单主表保持统一不增加活动字段。
 */
@Data
@TableName("shop_order_activity_relation")
public class OrderActivityRelation implements Serializable {

    @Serial
    private static final long serialVersionUID = 9067730400512687242L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private String orderSn;

    private Long orderGoodsId;

    private Long userId;

    private Integer activityType;

    private Long activityId;

    private Long activitySkuId;

    private Long goodsId;

    private Long productId;

    private BigDecimal activityPrice;

    private Integer number;

    private Integer inventoryStatus;

    private Date createTime;

    private Date updateTime;

    private Boolean delFlag;
}
