package com.zeroai.ai.mall.proposal;

/**
 * 确认失败原因码。
 * <p>
 * 每个码都必须有可理解的中文文案：确认失败是用户能直接看到的场景，
 * 「系统错误」这类笼统说法会让用户不知道该换商品、换地址还是重试。
 * <p>
 * 归属错误（跨用户确认、提议不存在）刻意共用同一码，
 * 避免通过错误码差异探测「这个提议编号是否存在」。
 */
public enum ProposalRejectCode {

    /** 提议不存在或不属于当前用户（对外不区分这两种情况）。 */
    PROPOSAL_NOT_FOUND("提议不存在或已失效，请重新发起下单"),
    /** 提议已过期。 */
    EXPIRED("下单提议已过期，请重新发起"),
    /** 提议已被确认过，返回既有订单号。 */
    ALREADY_CONFIRMED("该提议已确认过，请勿重复操作"),
    /** 另一个确认请求正在进行中。 */
    CONFIRMING_IN_PROGRESS("确认正在处理中，请稍候查看结果"),
    /** 购物车商品被删除或数量变化。 */
    CART_CHANGED("购物车内容已变化，请重新生成下单提议"),
    /** 商品价格与提议快照不一致。 */
    PRICE_CHANGED("商品价格已调整，请重新生成下单提议"),
    /** 库存不足。 */
    STOCK_INSUFFICIENT("商品库存不足，请调整数量或更换商品"),
    /** 优惠券不可用。 */
    COUPON_INVALID("优惠券已失效或不满足使用门槛"),
    /** 收货地址无效或不属于当前用户。 */
    ADDRESS_INVALID("收货地址无效，请重新选择"),
    /** 下单入口拒绝（购物车为空等业务前置条件）。 */
    ORDER_SUBMIT_REJECTED("当前无法下单，请检查购物车与收货地址后重试"),
    /** 系统异常，允许重试。 */
    SUBMIT_RETRYABLE("下单暂时失败，请稍后重试");

    private final String message;

    ProposalRejectCode(String message) {
        this.message = message;
    }

    /**
     * 对外文案，可直接展示给用户。
     *
     * @return 中文说明
     */
    public String getMessage() {
        return message;
    }
}
