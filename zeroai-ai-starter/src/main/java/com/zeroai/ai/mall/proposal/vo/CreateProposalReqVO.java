package com.zeroai.ai.mall.proposal.vo;

import java.util.List;

/**
 * 按勾选生成下单提议的请求体。
 * <p>
 * 只承载「用户选了什么」：不接收 userId（服务端从登录态取），
 * 也不接收价格、金额等可被篡改的字段——金额一律由服务端按实时购物车重算。
 *
 * @param addressId    收货地址 ID，可为空（服务端取默认地址）
 * @param userCouponId 用户优惠券 ID，可为空
 * @param cartIds      用户勾选的购物车 ID，不能为空
 */
public record CreateProposalReqVO(Long addressId, Long userCouponId, List<Long> cartIds) {
}
