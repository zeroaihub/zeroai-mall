package com.zeroai.domain.api.trade.enums;

/**
 * 订单活动库存状态。
 * 用于秒杀活动库存的幂等确认和释放，避免支付后置消息或延迟关单消息重复消费造成库存二次变更。
 */
public enum OrderActivityInventoryStatusEnum {

    /**
     * 下单已冻结活动库存。
     */
    LOCKED(0),

    /**
     * 支付成功已确认售出。
     */
    CONFIRMED(1),

    /**
     * 超时或取消已释放。
     */
    RELEASED(2);

    private final int status;

    OrderActivityInventoryStatusEnum(int status) {
        this.status = status;
    }

    /**
     * 获取库存状态值。
     *
     * @return 库存状态值
     */
    public int getStatus() {
        return status;
    }
}
