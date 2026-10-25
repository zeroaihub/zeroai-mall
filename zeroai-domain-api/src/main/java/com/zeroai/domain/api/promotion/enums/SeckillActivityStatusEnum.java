package com.zeroai.domain.api.promotion.enums;

/**
 * 秒杀活动状态。
 */
public enum SeckillActivityStatusEnum {

    /**
     * 草稿。
     */
    DRAFT(0),

    /**
     * 已发布。
     */
    PUBLISHED(1),

    /**
     * 已下架。
     */
    OFFLINE(2);

    private final int status;

    SeckillActivityStatusEnum(int status) {
        this.status = status;
    }

    /**
     * 获取状态值。
     *
     * @return 状态值
     */
    public int getStatus() {
        return status;
    }
}
