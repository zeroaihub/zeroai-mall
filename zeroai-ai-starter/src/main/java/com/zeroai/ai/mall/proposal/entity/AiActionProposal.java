package com.zeroai.ai.mall.proposal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 写操作提议（待人工确认）。
 * <p>
 * 本表只记录「用户还没确认的动作」，不参与订单、库存、支付任何业务数据流：
 * 订单落库仍由原 {@code local_message} → MQ → {@code shop_order} 链路负责。
 * 之所以新增表而不是复用 {@code local_message}，是因为后者是 outbox，
 * 语义是「待投递的内部消息」，没有「待用户确认」这一状态位，也缺价格/过期快照。
 * <p>
 * 幂等：{@code (user_id, idempotency_key)} 唯一，保证同一用户同一购物车快照只生成一条提议；
 * 状态迁移依赖条件 UPDATE，保证一个提议只能被成功确认一次。
 */
@Data
@TableName("ai_action_proposal")
public class AiActionProposal implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 对外提议编号，随机不可枚举。确认接口只收这个值，不收自增主键。
     */
    private String proposalNo;

    /**
     * 归属用户 ID，来自服务端鉴权结果。
     */
    private Long userId;

    /**
     * 动作类型，当前固定 CREATE_ORDER。
     */
    private String actionType;

    /**
     * 状态，取值见 {@link com.zeroai.ai.mall.proposal.ProposalStatus}。
     */
    private String status;

    /**
     * 幂等键：用户 + 地址 + 优惠券 + 购物车快照的 SHA-256 摘要。
     */
    private String idempotencyKey;

    /**
     * 收货地址 ID，确认时重新校验归属。
     */
    private Long addressId;

    /**
     * 用户优惠券 ID，可为空。
     */
    private Long userCouponId;

    /**
     * 参与下单的购物车 ID 列表，逗号分隔。
     */
    private String cartIds;

    /**
     * 商品快照，行式存储格式见 {@link com.zeroai.ai.mall.proposal.ProposalItem}。
     */
    private String itemsSnapshot;

    /**
     * 商品总价快照。
     */
    private BigDecimal goodsPrice;

    /**
     * 运费快照。
     */
    private BigDecimal freightPrice;

    /**
     * 优惠金额快照。
     */
    private BigDecimal couponPrice;

    /**
     * 应付金额快照，确认时与实时重算值比对。
     */
    private BigDecimal actualPrice;

    /**
     * 过期时间。超过该时间禁止确认，避免用户拿半小时前的价格下单。
     */
    private LocalDateTime expireTime;

    /**
     * 确认时间，仅在 CONFIRMED / REJECTED 后有值。
     */
    private LocalDateTime confirmTime;

    /**
     * 确认成功后生成的订单号，来自原下单入口。
     */
    private String orderSn;

    /**
     * 确认失败原因码，见 {@link com.zeroai.ai.mall.proposal.ProposalRejectCode}。
     */
    private String failCode;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    private LocalDateTime updateTime;
}
