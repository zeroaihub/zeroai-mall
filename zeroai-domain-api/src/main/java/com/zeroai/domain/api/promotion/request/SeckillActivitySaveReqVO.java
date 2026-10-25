package com.zeroai.domain.api.promotion.request;

import com.zeroai.domain.api.promotion.entity.SeckillActivity;
import com.zeroai.domain.api.promotion.entity.SeckillSku;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 秒杀活动保存请求。
 */
@Data
public class SeckillActivitySaveReqVO {

    /**
     * 活动主信息。
     */
    private SeckillActivity activity;

    /**
     * 活动 SKU 列表。
     */
    private List<SeckillSku> skuList = new ArrayList<>();
}
