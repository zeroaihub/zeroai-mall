package com.zeroai.domain.api.promotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 秒杀活动 SKU。
 * 保存活动库存三态，普通订单库存仍以 shop_goods_product 为最终库存兜底。
 */
@Data
@TableName("shop_seckill_sku")
public class SeckillSku implements Serializable {

    @Serial
    private static final long serialVersionUID = -623470414283550255L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long activityId;

    private Long goodsId;

    private Long productId;

    private BigDecimal seckillPrice;

    private Integer availableStock;

    private Integer lockedStock;

    private Integer soldStock;

    private Integer limitCount;

    private Integer status;

    private Date createTime;

    private Date updateTime;

    private Boolean delFlag;
}
