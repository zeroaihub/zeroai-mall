package com.zeroai.ai.mall.proposal;

/**
 * 写操作提议状态机。
 * <p>
 * 状态迁移只能通过 {@code AiActionProposalMapper} 的条件 UPDATE 完成，
 * 保证并发确认下只有一个请求能从 {@link #PENDING} 迁到 {@link #CONFIRMING}。
 * <p>
 * 合法迁移：
 * <pre>
 *   PENDING   -> CONFIRMING  确认请求抢锁成功
 *   PENDING   -> EXPIRED     超过 expire_time，惰性判定
 *   CONFIRMING-> CONFIRMED   下单入口返回订单号
 *   CONFIRMING-> REJECTED    价格/库存/优惠/归属校验失败
 *   CONFIRMING-> PENDING     下单入口异常且未落单，允许重试
 * </pre>
 * 终态为 {@link #CONFIRMED} / {@link #REJECTED} / {@link #EXPIRED}，不再迁移。
 */
public enum ProposalStatus {

    /** 待确认：已生成提议，尚未触发订单写入。 */
    PENDING,

    /** 确认中：请求已抢到状态锁，正在做业务重校验与下单，此时不允许再次确认。 */
    CONFIRMING,

    /** 已确认：订单已走原下单入口，order_sn 已回写。 */
    CONFIRMED,

    /** 已拒绝：业务重校验失败（价格变化、库存不足、优惠券失效等），需重新生成提议。 */
    REJECTED,

    /** 已过期：超过允许确认的时限。 */
    EXPIRED;

    /**
     * 判断是否为终态。终态禁止任何再次迁移。
     *
     * @return true 表示不会再变化
     */
    public boolean isTerminal() {
        return this == CONFIRMED || this == REJECTED || this == EXPIRED;
    }
}
