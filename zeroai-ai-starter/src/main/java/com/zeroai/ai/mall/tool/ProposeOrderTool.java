package com.zeroai.ai.mall.tool;

import com.zeroai.ai.mall.proposal.ActionProposalService;
import com.zeroai.ai.mall.proposal.vo.ProposalVO;
import com.zeroai.util.exception.BusinessException;
import io.agentscope.core.tool.Tool;

import java.util.function.Consumer;

/**
 * 提议工具：生成「创建订单」待确认提议。
 * <p>
 * <b>这是 Agent 唯一具备「写」性质的工具，但它只写提议表，不创建订单。</b>
 * 订单写入只能由用户通过独立确认接口触发（见 {@code ActionProposalService#confirm}）。
 * 把「提议」和「执行」拆成两个入口，正是 Phase 6 Human-in-the-loop 的核心：
 * 模型可以准备动作，但永远不能替用户按下确认键。
 * <p>
 * userId 在构造时由服务端注入，模型无法指定他人身份。
 */
public class ProposeOrderTool {

    private final ActionProposalService proposalService;
    private final Long userId;
    private final Consumer<ProposalVO> proposalListener;

    /**
     * @param proposalService  提议服务
     * @param userId           当前登录用户 ID，必须非空
     * @param proposalListener 提议生成后的回调，用于下发 confirm_action 事件；可为 null
     */
    public ProposeOrderTool(ActionProposalService proposalService, Long userId,
                            Consumer<ProposalVO> proposalListener) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空：未登录用户不允许生成下单提议");
        }
        this.proposalService = proposalService;
        this.userId = userId;
        this.proposalListener = proposalListener;
    }

    /**
     * 生成下单提议。<b>不会创建订单</b>，必须由用户确认后才会下单。
     *
     * @param addressId    收货地址 ID，来自 list_my_addresses
     * @param userCouponId 用户优惠券 ID，可不填
     * @param cartIds      购物车 ID 列表；不填表示取当前用户已勾选商品
     * @return JSON 字符串，含提议编号、金额摘要、过期时间与「需用户确认」提示
     */
    @Tool(name = "propose_order",
            description = "为当前用户生成「创建订单」的待确认提议。此操作不会创建订单，必须由用户在页面上点击确认后才会真正下单。"
                    + "参数：addressId 必填（来自 list_my_addresses）；userCouponId 可选；cartIds 可选（不填取已勾选商品）。"
                    + "返回提议编号、金额与过期时间。",
            readOnly = false,
            concurrencySafe = false)
    public String proposeOrder(Long addressId, Long userCouponId, java.util.List<Long> cartIds) {
        if (addressId == null) {
            return "{\"error\":\"MISSING_ADDRESS\",\"hint\":\"需要先通过 list_my_addresses 让用户选择收货地址\"}";
        }
        ProposalVO proposal;
        try {
            proposal = proposalService.createOrderProposal(userId, addressId, userCouponId, cartIds);
        } catch (BusinessException e) {
            // 业务异常文案本来就是给用户看的（如「收货地址无效」），可以回显
            return "{\"error\":\"PROPOSAL_REJECTED\",\"hint\":" + ToolJson.str(e.getMsg()) + "}";
        } catch (IllegalArgumentException e) {
            // 入参校验失败：消息是面向用户的校验说明
            return "{\"error\":\"PROPOSAL_INVALID\",\"hint\":" + ToolJson.str(e.getMessage()) + "}";
        } catch (RuntimeException e) {
            // 其余一律按系统异常处理，不回显内部信息
            return "{\"error\":\"PROPOSAL_FAILED\",\"hint\":\"下单提议生成失败，请稍后再试\"}";
        }
        if (proposalListener != null) {
            try {
                proposalListener.accept(proposal);
            } catch (RuntimeException e) {
                // 事件下发失败不影响工具结果：用户仍可在列表里看到提议
            }
        }
        return toJson(proposal);
    }

    /**
     * 提议视图转 JSON。金额用 plainString，避免科学计数法。
     *
     * @param proposal 提议
     * @return JSON 字符串
     */
    private static String toJson(ProposalVO proposal) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"proposalNo\":").append(ToolJson.str(proposal.proposalNo()))
                .append(",\"status\":").append(ToolJson.str(proposal.status()))
                .append(",\"actualPrice\":").append(ToolJson.num(proposal.actualPrice()))
                .append(",\"goodsPrice\":").append(ToolJson.num(proposal.goodsPrice()))
                .append(",\"freightPrice\":").append(ToolJson.num(proposal.freightPrice()))
                .append(",\"couponPrice\":").append(ToolJson.num(proposal.couponPrice()))
                .append(",\"expireAt\":").append(ToolJson.str(proposal.expireAt()))
                .append(",\"itemCount\":").append(proposal.items() == null ? 0 : proposal.items().size())
                .append(",\"message\":").append(ToolJson.str(proposal.message()))
                .append('}');
        return sb.toString();
    }
}
