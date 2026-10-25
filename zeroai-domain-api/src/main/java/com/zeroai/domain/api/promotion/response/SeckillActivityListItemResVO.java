package com.zeroai.domain.api.promotion.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

/**
 * 秒杀活动列表响应项。
 * 面向后台和移动端活动列表展示，避免接口直接暴露数据库实体字段。
 */
@Data
public class SeckillActivityListItemResVO {

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
}
