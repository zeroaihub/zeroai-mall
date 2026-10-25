package com.zeroai.domain.api.promotion.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 秒杀活动 SKU 响应。
 * 对外展示活动商品、秒杀价和活动库存三态，隐藏删除标记等持久化字段。
 */
@Data
public class SeckillSkuResVO {

    private Long id;

    private Long activityId;

    private Long goodsId;

    private Long productId;

    /**
     * 商品名称。
     */
    private String goodsName;

    /**
     * 商品主图。
     */
    private String goodsPicUrl;

    /**
     * 货品图片。
     */
    private String productPicUrl;

    /**
     * 移动端展示图片，优先使用货品图片，缺失时回退商品主图。
     */
    private String picUrl;

    /**
     * 货品规格值。
     */
    private String[] specifications;

    private BigDecimal seckillPrice;

    private Integer availableStock;

    private Integer lockedStock;

    private Integer soldStock;

    private Integer limitCount;

    private Integer status;
}
