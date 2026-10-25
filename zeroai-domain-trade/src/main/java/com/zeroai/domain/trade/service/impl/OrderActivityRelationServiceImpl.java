package com.zeroai.domain.trade.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zeroai.domain.api.trade.entity.OrderActivityRelation;
import com.zeroai.domain.api.trade.enums.OrderActivityInventoryStatusEnum;
import com.zeroai.domain.api.trade.enums.OrderActivityTypeEnum;
import com.zeroai.domain.api.trade.mapper.OrderActivityRelationMapper;
import com.zeroai.domain.api.trade.service.IOrderActivityRelationService;
import org.springframework.stereotype.Service;

/**
 * 订单活动关联服务实现。
 */
@Service
public class OrderActivityRelationServiceImpl
        extends ServiceImpl<OrderActivityRelationMapper, OrderActivityRelation>
        implements IOrderActivityRelationService {

    /**
     * 根据订单号查询订单活动关联。
     *
     * @param orderSn 订单号
     * @return 订单活动关联
     */
    @Override
    public OrderActivityRelation getByOrderSn(String orderSn) {
        return getOne(Wrappers.lambdaQuery(OrderActivityRelation.class)
                .eq(OrderActivityRelation::getOrderSn, orderSn)
                .last("limit 1"));
    }

    /**
     * 判断用户是否已经存在同一秒杀活动同一商品的有效购买记录。
     * 秒杀 60 秒超时释放后会把库存状态改为 RELEASED，此时允许用户重新参与抢购；其余状态都视为已购买或处理中。
     *
     * @param activityId 秒杀活动 ID
     * @param goodsId 商品 ID
     * @param userId 用户 ID
     * @return true=存在有效购买记录
     */
    @Override
    public boolean existsActiveSeckillPurchase(Long activityId, Long goodsId, Long userId) {
        if (activityId == null || goodsId == null || userId == null) {
            return false;
        }
        return count(Wrappers.lambdaQuery(OrderActivityRelation.class)
                .eq(OrderActivityRelation::getActivityType, OrderActivityTypeEnum.SECKILL.getType())
                .eq(OrderActivityRelation::getActivityId, activityId)
                .eq(OrderActivityRelation::getGoodsId, goodsId)
                .eq(OrderActivityRelation::getUserId, userId)
                .ne(OrderActivityRelation::getInventoryStatus, OrderActivityInventoryStatusEnum.RELEASED.getStatus())) > 0;
    }
}
