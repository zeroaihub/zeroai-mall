package com.zeroai.domain.api.promotion.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 秒杀活动详情响应。
 */
@Data
public class SeckillActivityDetailResVO {

    private SeckillActivityListItemResVO activity;

    private List<SeckillSkuResVO> skuList = new ArrayList<>();
}
