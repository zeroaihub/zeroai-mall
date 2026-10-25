package com.zeroai.domain.api.trade.enums;

/**
 * 订单活动类型。
 * 订单主表保持统一，活动维度通过 shop_order_activity_relation 关联，避免在 shop_order 上不断追加活动字段。
 */
public enum OrderActivityTypeEnum {

    /**
     * 秒杀活动。
     */
    SECKILL(1);

    private final int type;

    OrderActivityTypeEnum(int type) {
        this.type = type;
    }

    /**
     * 获取活动类型值。
     *
     * @return 活动类型值
     */
    public int getType() {
        return type;
    }
}
