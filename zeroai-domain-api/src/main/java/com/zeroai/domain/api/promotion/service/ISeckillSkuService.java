package com.zeroai.domain.api.promotion.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zeroai.domain.api.promotion.entity.SeckillSku;

/**
 * 秒杀活动 SKU 服务。
 */
public interface ISeckillSkuService extends IService<SeckillSku> {

    /**
     * 根据活动 ID 物理删除秒杀 SKU。
     * 更新活动维护 SKU 列表时使用，避免逻辑删除记录继续占用活动货品唯一键。
     *
     * @param activityId 活动 ID
     * @return 删除行数
     */
    int physicalDeleteByActivityId(Long activityId);
}
