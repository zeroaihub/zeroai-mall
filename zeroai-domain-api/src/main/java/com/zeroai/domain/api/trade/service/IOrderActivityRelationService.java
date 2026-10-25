package com.zeroai.domain.api.trade.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zeroai.domain.api.trade.entity.OrderActivityRelation;

/**
 * 订单活动关联服务。
 */
public interface IOrderActivityRelationService extends IService<OrderActivityRelation> {

    /**
     * 根据订单号查询订单活动关联。
     *
     * @param orderSn 订单号
     * @return 订单活动关联
     */
    OrderActivityRelation getByOrderSn(String orderSn);

    /**
     * 判断用户是否已经存在同一秒杀活动同一商品的有效购买记录。
     * 已释放库存的超时订单不视为有效购买，避免用户订单 60 秒超时释放后无法再次抢购。
     *
     * @param activityId 秒杀活动 ID
     * @param goodsId 商品 ID
     * @param userId 用户 ID
     * @return true=已存在有效购买记录
     */
    boolean existsActiveSeckillPurchase(Long activityId, Long goodsId, Long userId);
}
