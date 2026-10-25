package com.zeroai.domain.promotion.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zeroai.domain.api.promotion.entity.SeckillSku;
import com.zeroai.domain.api.promotion.mapper.SeckillSkuMapper;
import com.zeroai.domain.api.promotion.service.ISeckillSkuService;
import org.springframework.stereotype.Service;

/**
 * 秒杀活动 SKU 服务实现。
 */
@Service
public class SeckillSkuServiceImpl extends ServiceImpl<SeckillSkuMapper, SeckillSku> implements ISeckillSkuService {

    /**
     * 根据活动 ID 物理删除秒杀 SKU。
     * MyBatis-Plus 全局逻辑删除会让旧记录继续占用唯一键，因此活动 SKU 重建场景必须走 mapper 原生 DELETE。
     *
     * @param activityId 活动 ID
     * @return 删除行数
     */
    @Override
    public int physicalDeleteByActivityId(Long activityId) {
        if (activityId == null) {
            return 0;
        }
        return baseMapper.physicalDeleteByActivityId(activityId);
    }
}
