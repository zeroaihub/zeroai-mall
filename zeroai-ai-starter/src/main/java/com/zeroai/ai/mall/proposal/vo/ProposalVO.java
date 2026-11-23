package com.zeroai.ai.mall.proposal.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 提议对外视图。
 * <p>
 * 只暴露用户确认决策需要的字段，不暴露内部主键、幂等键、快照原文；
 * 商品名与价格来自用户自己的购物车，可以展示。
 * <p>
 * 收货地址必须下发摘要：用户点「确认下单」之前要能核对「这一单寄到哪」，
 * 只给金额和件数等于让他盲签。摘要含收件人、脱敏手机号与省市区门牌，
 * 但不下发手机号原值，也不下发 addressId 以外的地址内部字段。
 *
 * @param proposalNo  对外提议编号
 * @param status      状态，取值见 {@code ProposalStatus}
 * @param actionType  动作类型，当前固定 CREATE_ORDER
 * @param actualPrice 应付金额快照
 * @param goodsPrice  商品总价快照
 * @param freightPrice 运费快照
 * @param couponPrice 优惠金额快照
 * @param expireAt    过期时间（ISO-8601）
 * @param addressText 收货地址摘要，地址已被删除时为 null
 * @param items       商品行摘要
 * @param orderSn     确认成功后的订单号，未确认时为 null
 * @param failCode    确认失败原因码，成功时为 null
 * @param message     面向用户的说明文案
 */
public record ProposalVO(String proposalNo,
                         String status,
                         String actionType,
                         BigDecimal actualPrice,
                         BigDecimal goodsPrice,
                         BigDecimal freightPrice,
                         BigDecimal couponPrice,
                         String expireAt,
                         String addressText,
                         List<ItemVO> items,
                         String orderSn,
                         String failCode,
                         String message) {

    /**
     * 提议中的单条商品摘要，不含 SKU 内部库存等敏感字段。
     *
     * @param goodsName 商品名
     * @param unitPrice 单价快照
     * @param number    数量
     */
    public record ItemVO(String goodsName, BigDecimal unitPrice, Integer number) {
    }
}
