package com.zeroai.ai.mall.proposal.vo;

import com.zeroai.ai.mall.proposal.ProposalRejectCode;

import java.math.BigDecimal;

/**
 * 确认结果视图。
 * <p>
 * 三种形态，前端必须分开处理：
 * <ul>
 *   <li>{@code ok=true, processing=false}：订单已真正落库，{@code orderSn} 可信；</li>
 *   <li>{@code ok=true, processing=true}：消息已入队但消费端还没回结果，
 *       订单<b>可能还没生成</b>，前端应继续轮询提议状态直到出结果；</li>
 *   <li>{@code ok=false}：明确没有创建订单，{@code failCode} 给出原因。</li>
 * </ul>
 * 之所以要区分第二种：下单是异步的，确认接口无法在一次请求内保证落库。
 * 早期实现一律返回成功，消费端没启动时用户会看到「已下单 + 订单号」却查不到订单，
 * 这是个会让人误以为丢单的严重误导。
 *
 * @param ok          是否确认成功（含处理中，处理中也不意味着失败）
 * @param processing  是否需要继续等待落单结果
 * @param proposalNo  提议编号
 * @param orderSn     成功或处理中时的订单号，失败为 null
 * @param actualPrice 成功或处理中时的应付金额，失败为 null
 * @param failCode    失败原因码，成功为 null
 * @param message     面向用户的说明文案
 */
public record ConfirmResVO(boolean ok,
                           boolean processing,
                           String proposalNo,
                           String orderSn,
                           BigDecimal actualPrice,
                           String failCode,
                           String message) {

    /**
     * 构造成功结果（订单已落库）。
     *
     * @param proposalNo  提议编号
     * @param orderSn     订单号
     * @param actualPrice 应付金额
     * @return 成功结果
     */
    public static ConfirmResVO success(String proposalNo, String orderSn, BigDecimal actualPrice) {
        return new ConfirmResVO(true, false, proposalNo, orderSn, actualPrice, null,
                "订单已创建，请到「我的订单」完成支付");
    }

    /**
     * 构造处理中结果：消息已投递，但消费端尚未写回结果。
     *
     * @param proposalNo  提议编号
     * @param orderSn     订单号
     * @param actualPrice 应付金额
     * @return 处理中结果
     */
    public static ConfirmResVO processing(String proposalNo, String orderSn, BigDecimal actualPrice) {
        return new ConfirmResVO(true, true, proposalNo, orderSn, actualPrice, null,
                "订单正在创建中，请稍候到「我的订单」查看");
    }

    /**
     * 构造失败结果，文案取自原因码。
     *
     * @param proposalNo 提议编号
     * @param code       失败原因码
     * @return 失败结果
     */
    public static ConfirmResVO failure(String proposalNo, ProposalRejectCode code) {
        return failure(proposalNo, code, code.getMessage());
    }

    /**
     * 构造失败结果，使用调用方给出的具体文案。
     * <p>
     * 消费端拒绝时能拿到比原因码更具体的信息（例如「库存不足」），
     * 这类信息对用户远比笼统的「当前无法下单」有用。
     *
     * @param proposalNo 提议编号
     * @param code       失败原因码
     * @param message    面向用户的具体说明
     * @return 失败结果
     */
    public static ConfirmResVO failure(String proposalNo, ProposalRejectCode code, String message) {
        return new ConfirmResVO(false, false, proposalNo, null, null, code.name(), message);
    }
}
