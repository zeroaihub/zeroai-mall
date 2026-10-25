package com.zeroai.domain.trade.support.seckill;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zeroai.domain.api.promotion.entity.SeckillSku;
import com.zeroai.domain.api.promotion.service.ISeckillSkuService;
import com.zeroai.domain.api.trade.entity.OrderActivityRelation;
import com.zeroai.domain.api.trade.enums.OrderActivityInventoryStatusEnum;
import com.zeroai.domain.api.trade.enums.OrderActivityTypeEnum;
import com.zeroai.domain.api.trade.service.IOrderActivityRelationService;
import com.zeroai.util.enums.ReturnCodeEnum;
import com.zeroai.util.exception.BusinessException;
import lombok.AllArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 秒杀活动库存确认支撑服务。
 * 支付成功后把活动库存从冻结态转为已售态，并通过订单活动关联状态保证本地消息重试幂等。
 */
@Service
@AllArgsConstructor
public class SeckillInventoryConfirmSupport {

    private final IOrderActivityRelationService orderActivityRelationService;
    private final ISeckillSkuService seckillSkuService;

    /**
     * 按订单 ID 确认秒杀活动库存。
     *
     * @param orderId 订单 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirmPaidStock(Long orderId) {
        if (orderId == null) {
            return;
        }
        List<OrderActivityRelation> relations = orderActivityRelationService.list(Wrappers.lambdaQuery(OrderActivityRelation.class)
                .eq(OrderActivityRelation::getOrderId, orderId)
                .eq(OrderActivityRelation::getActivityType, OrderActivityTypeEnum.SECKILL.getType()));
        if (CollectionUtils.isEmpty(relations)) {
            return;
        }
        for (OrderActivityRelation relation : relations) {
            confirmSingleRelation(relation);
        }
    }

    /**
     * 确认单条订单活动关联的秒杀库存。
     *
     * @param relation 订单活动关联
     */
    private void confirmSingleRelation(OrderActivityRelation relation) {
        Integer number = relation.getNumber() == null ? 0 : relation.getNumber();
        if (relation.getId() == null || relation.getActivitySkuId() == null || number <= 0) {
            return;
        }
        boolean relationMarked = orderActivityRelationService.update(Wrappers.lambdaUpdate(OrderActivityRelation.class)
                .set(OrderActivityRelation::getInventoryStatus, OrderActivityInventoryStatusEnum.CONFIRMED.getStatus())
                .set(OrderActivityRelation::getUpdateTime, new Date())
                .eq(OrderActivityRelation::getId, relation.getId())
                .eq(OrderActivityRelation::getInventoryStatus, OrderActivityInventoryStatusEnum.LOCKED.getStatus()));
        if (!relationMarked) {
            // 已确认或已释放说明这条关联已被其他路径处理，本次重试不再重复变更活动库存。
            return;
        }
        boolean confirmed = seckillSkuService.update(Wrappers.lambdaUpdate(SeckillSku.class)
                .setSql("locked_stock = locked_stock - " + number)
                .setSql("sold_stock = sold_stock + " + number)
                .set(SeckillSku::getUpdateTime, new Date())
                .eq(SeckillSku::getId, relation.getActivitySkuId())
                .ge(SeckillSku::getLockedStock, number));
        if (!confirmed) {
            throw new BusinessException(ReturnCodeEnum.ORDER_SUBMIT_ERROR, "秒杀活动库存确认失败");
        }
    }
}
