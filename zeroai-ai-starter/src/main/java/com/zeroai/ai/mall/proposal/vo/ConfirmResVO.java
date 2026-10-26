package com.zeroai.ai.mall.proposal.vo;

import com.zeroai.ai.mall.proposal.ProposalRejectCode;

import java.math.BigDecimal;

/**
 * 确认结果视图。
 * <p>
 * 成功时给订单号；失败时给可理解的原因码与文案。
 * 无论是成功还是失败，{@code ok=false} 都意味着<b>没有创建任何订单</b>。
 *
 * @param ok          是否确认成功
 * @param proposalNo  提议编号
 * @param orderSn     成功时的订单号，失败为 null
 * @param actualPrice 成功时的应付金额，失败为 null
 * @param failCode    失败原因码，成功为 null
 * @param message     面向用户的说明文案
 */
public record ConfirmResVO(boolean ok,
                           String proposalNo,
                           String orderSn,
                           BigDecimal actualPrice,
                           String failCode,
                           String message) {

    /**
     * 构造成功结果。
     *
     * @param proposalNo  提议编号
     * @param orderSn     订单号
     * @param actualPrice 应付金额
     * @return 成功结果
     */
    public static ConfirmResVO success(String proposalNo, String orderSn, BigDecimal actualPrice) {
        return new ConfirmResVO(true, proposalNo, orderSn, actualPrice, null, "下单请求已提交，请稍候查看订单状态");
    }

    /**
     * 构造失败结果。
     *
     * @param proposalNo 提议编号
     * @param code       失败原因码
     * @return 失败结果，文案取自原因码
     */
    public static ConfirmResVO failure(String proposalNo, ProposalRejectCode code) {
        return new ConfirmResVO(false, proposalNo, null, null, code.name(), code.getMessage());
    }
}
