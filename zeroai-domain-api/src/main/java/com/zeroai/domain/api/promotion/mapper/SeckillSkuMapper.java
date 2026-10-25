package com.zeroai.domain.api.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zeroai.domain.api.promotion.entity.SeckillSku;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 秒杀活动 SKU Mapper。
 */
public interface SeckillSkuMapper extends BaseMapper<SeckillSku> {

    /**
     * 根据活动 ID 物理删除秒杀 SKU。
     * 自定义 DELETE 用于绕过 MyBatis-Plus 全局逻辑删除，避免旧 SKU 继续占用活动货品唯一键。
     *
     * @param activityId 活动 ID
     * @return 删除行数
     */
    @Delete("DELETE FROM shop_seckill_sku WHERE activity_id = #{activityId}")
    int physicalDeleteByActivityId(@Param("activityId") Long activityId);
}
