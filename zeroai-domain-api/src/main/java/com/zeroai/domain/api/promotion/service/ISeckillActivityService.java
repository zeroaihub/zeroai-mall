package com.zeroai.domain.api.promotion.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.zeroai.domain.api.promotion.entity.SeckillActivity;
import com.zeroai.domain.api.promotion.request.SeckillActivitySaveReqVO;
import com.zeroai.domain.api.promotion.response.SeckillActivityDetailResVO;

/**
 * 秒杀活动服务。
 */
public interface ISeckillActivityService extends IService<SeckillActivity> {

    /**
     * 分页查询后台秒杀活动。
     *
     * @param page 分页
     * @param query 查询条件
     * @return 活动分页
     */
    IPage<SeckillActivity> listPage(Page<SeckillActivity> page, SeckillActivity query);

    /**
     * 保存秒杀活动和活动 SKU。
     *
     * @param reqVO 保存请求
     */
    void saveActivity(SeckillActivitySaveReqVO reqVO);

    /**
     * 更新秒杀活动和活动 SKU。
     *
     * @param reqVO 保存请求
     */
    void updateActivity(SeckillActivitySaveReqVO reqVO);

    /**
     * 查询秒杀活动详情。
     *
     * @param activityId 活动 ID
     * @return 活动详情
     */
    SeckillActivityDetailResVO detail(Long activityId);

    /**
     * 发布秒杀活动。
     *
     * @param activityId 活动 ID
     */
    void publish(Long activityId);

    /**
     * 下架秒杀活动。
     *
     * @param activityId 活动 ID
     */
    void offline(Long activityId);

    /**
     * 预热活动 SKU 库存到 Redis。
     *
     * @param activityId 活动 ID
     */
    void preheat(Long activityId);
}
