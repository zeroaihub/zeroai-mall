package com.zeroai.domain.api.promotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 秒杀活动主表。
 * 只保存活动维度的展示、时间和发布状态，具体商品库存配置落到 SeckillSku。
 */
@Data
@TableName("shop_seckill_activity")
public class SeckillActivity implements Serializable {

    @Serial
    private static final long serialVersionUID = -3179395389862595461L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String brief;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    private Integer status;

    private Integer sortOrder;

    private Date createTime;

    private Date updateTime;

    private Boolean delFlag;
}
