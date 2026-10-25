package com.zeroai.domain.trade.support.seckill;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zeroai.domain.api.promotion.entity.SeckillSku;
import com.zeroai.domain.api.promotion.service.ISeckillSkuService;
import com.zeroai.domain.api.trade.entity.OrderActivityRelation;
import com.zeroai.domain.api.trade.enums.OrderActivityInventoryStatusEnum;
import com.zeroai.domain.api.trade.service.IOrderActivityRelationService;
import com.zeroai.domain.promotion.support.seckill.SeckillRedisKeySupport;
import com.zeroai.data.redis.manager.RedisCache;
import com.zeroai.util.enums.ReturnCodeEnum;
import com.zeroai.util.exception.BusinessException;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 秒杀库存释放支撑服务。
 * 只处理秒杀活动库存和 Redis 抢购资格回补；普通 SKU 冻结库存释放仍由订单取消支撑服务负责。
 */
@Service
@AllArgsConstructor
public class SeckillInventoryReleaseSupport {

    private final ISeckillSkuService seckillSkuService;
    private final IOrderActivityRelationService orderActivityRelationService;
    private final RedisCache redisCache;

    /**
     * 释放超时未支付订单占用的秒杀活动库存。
     *
     * @param relation 订单活动关联
     */
    @Transactional(rollbackFor = Exception.class)
    public void releaseTimeoutStock(OrderActivityRelation relation) {
        if (relation == null || relation.getActivitySkuId() == null || relation.getActivityId() == null
                || relation.getGoodsId() == null || relation.getUserId() == null) {
            return;
        }
        Integer number = relation.getNumber() == null ? 0 : relation.getNumber();
        if (number > 0) {
            boolean relationMarked = orderActivityRelationService.update(Wrappers.lambdaUpdate(OrderActivityRelation.class)
                    .set(OrderActivityRelation::getInventoryStatus, OrderActivityInventoryStatusEnum.RELEASED.getStatus())
                    .set(OrderActivityRelation::getUpdateTime, new Date())
                    .eq(OrderActivityRelation::getId, relation.getId())
                    .eq(OrderActivityRelation::getInventoryStatus, OrderActivityInventoryStatusEnum.LOCKED.getStatus()));
            if (!relationMarked) {
                // 关联库存状态不是 LOCKED，说明已支付确认或已经释放，不能重复回补活动库存。
                return;
            }
            boolean released = seckillSkuService.update(Wrappers.lambdaUpdate(SeckillSku.class)
                    .setSql("available_stock = available_stock + " + number)
                    .setSql("locked_stock = locked_stock - " + number)
                    .set(SeckillSku::getUpdateTime, new Date())
                    .eq(SeckillSku::getId, relation.getActivitySkuId())
                    .ge(SeckillSku::getLockedStock, number));
            if (!released) {
                throw new BusinessException(ReturnCodeEnum.ORDER_SUBMIT_ERROR, "秒杀活动库存释放失败");
            }
            // Redis 秒杀库存是入口快照，只能按释放数量递增，不能覆盖当前库存值。
            redisCache.incrementCacheObject(SeckillRedisKeySupport.stockKey(relation.getActivitySkuId()), number);
        }
        redisCache.deleteObject(SeckillRedisKeySupport.userPurchaseKey(relation.getActivityId(), relation.getGoodsId(), relation.getUserId()));
    }
}
