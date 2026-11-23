package com.zeroai.ai.mall.tool;

import com.zeroai.ai.mall.event.CartSelection;
import com.zeroai.ai.mall.event.GuideUiEvents;
import com.zeroai.ai.mall.proposal.ActionProposalService;
import com.zeroai.ai.mall.proposal.ProposalValidationException;
import com.zeroai.ai.mall.proposal.vo.ProposalVO;
import com.zeroai.util.exception.BusinessException;
import io.agentscope.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 提议工具：生成「创建订单」待确认提议。
 * <p>
 * <b>这是 Agent 唯一具备「写」性质的工具，但它只写提议表，不创建订单。</b>
 * 订单写入只能由用户通过独立确认接口触发（见 {@code ActionProposalService#confirm}）。
 * 把「提议」和「执行」拆成两个入口，正是 Phase 6 Human-in-the-loop 的核心：
 * 模型可以准备动作，但永远不能替用户按下确认键。
 * <p>
 * 下单商品的选择方式（2026-11 调整）：调用方通常<b>不传</b> cartIds，
 * 此时本工具不再直接建提议，而是把购物车下发到前端弹层，由用户勾选后走独立接口生成提议。
 * 原因是「生成提议要求商品已在购物车页勾选」把用户赶出了对话：说要下单 → 跳去勾选 → 回来再说一遍。
 * 只有当用户在前文明确点名「只买这几件」时，模型才会传 cartIds 并直接建提议。
 * <p>
 * userId 在构造时由服务端注入，模型无法指定他人身份。
 */
public class ProposeOrderTool {

    private static final Logger log = LoggerFactory.getLogger(ProposeOrderTool.class);

    /** 随提议一起回传给模型的固定提示：确认语义与「不许增删商品」。 */
    private static final String CONFIRM_HINT =
            "这只是提议，需要用户确认后才会创建订单；商品清单以 items 为准，不要自行增加或替换";

    private final ActionProposalService proposalService;
    private final Long userId;
    private final GuideUiEvents events;

    /**
     * @param proposalService 提议服务
     * @param userId          当前登录用户 ID，必须非空
     * @param events          前端事件出口，可为 null（等价于 {@link GuideUiEvents#NOOP}）
     */
    public ProposeOrderTool(ActionProposalService proposalService, Long userId, GuideUiEvents events) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空：未登录用户不允许生成下单提议");
        }
        this.proposalService = proposalService;
        this.userId = userId;
        this.events = events == null ? GuideUiEvents.NOOP : events;
    }

    /**
     * 准备下单：默认先把购物车弹给用户勾选，只有显式指定商品时才直接建提议。
     * <p>
     * <b>不会创建订单</b>，最多写一条待确认提议。副作用：可能下发一次 {@code cart_select} 事件。
     *
     * @param addressId    收货地址 ID，来自 list_my_addresses；为空时服务端取默认地址
     * @param userCouponId 用户优惠券 ID，可不填
     * @param cartIds      购物车 ID 列表；不填表示先让用户勾选，
     *                     填了则每一项都必须已勾选，否则服务端拒绝
     * @return JSON 字符串：已生成提议时含提议摘要；等待勾选时含 NEED_CART_SELECTION 状态
     */
    @Tool(name = "propose_order",
            description = "为当前用户准备下单：默认会在对话内弹出购物车让用户勾选商品，此时不会立即生成提议；"
                    + "只有在用户已明确点名具体商品时才传 cartIds 直接生成提议。"
                    + "addressId 可为空（服务端取默认地址）；userCouponId 可选。",
            readOnly = false,
            concurrencySafe = false)
    public String proposeOrder(Long addressId, Long userCouponId, java.util.List<Long> cartIds) {
        log.info("工具调用 propose_order userId={} addressId={} couponId={} cartIds={}",
                userId, addressId, userCouponId, cartIds);
        if (cartIds == null || cartIds.isEmpty()) {
            return awaitCartSelection(addressId, userCouponId);
        }
        ProposalVO proposal;
        try {
            proposal = proposalService.createOrderProposal(userId, addressId, userCouponId, cartIds);
        } catch (ProposalValidationException e) {
            log.info("工具拒绝 propose_order userId={} code={} detail={}", userId, e.getCode(), e.getDetail());
            // 校验失败要带稳定错误码：模型据此知道「去勾选」「重新选商品」，而不是换参数反复重试
            StringBuilder sb = new StringBuilder(160);
            sb.append("{\"error\":").append(ToolJson.str(e.getCode().name()))
                    .append(",\"hint\":").append(ToolJson.str(e.getCode().getMessage()));
            if (!e.getDetail().isBlank()) {
                sb.append(",\"detail\":").append(ToolJson.str(e.getDetail()));
            }
            return sb.append('}').toString();
        } catch (BusinessException e) {
            log.info("工具拒绝 propose_order userId={} msg={}", userId, e.getMsg());
            // 业务异常文案本来就是给用户看的（如「收货地址无效」），可以回显
            return "{\"error\":\"PROPOSAL_REJECTED\",\"hint\":" + ToolJson.str(e.getMsg()) + "}";
        } catch (IllegalArgumentException e) {
            // 入参校验失败：消息是面向用户的校验说明
            return "{\"error\":\"PROPOSAL_INVALID\",\"hint\":" + ToolJson.str(e.getMessage()) + "}";
        } catch (RuntimeException e) {
            // 其余一律按系统异常处理，不回显内部信息
            return "{\"error\":\"PROPOSAL_FAILED\",\"hint\":\"下单提议生成失败，请稍后再试\"}";
        }
        log.info("工具结果 propose_order userId={} proposalNo={} actualPrice={} items={}",
                userId, proposal.proposalNo(), proposal.actualPrice(),
                proposal.items() == null ? 0 : proposal.items().size());
        pushProposal(proposal);
        return toJson(proposal);
    }

    /**
     * 把购物车下发到前端弹层，等待用户勾选。
     * <p>
     * 未登录、购物车为空、没有收货地址都属于「可预期的用户态」，返回结构化提示让模型引导用户，
     * 而不是抛异常——抛异常会终止整轮回答，用户只看到一句「服务繁忙」。
     *
     * @param addressId    收货地址 ID，可为空
     * @param userCouponId 优惠券 ID，可为空
     * @return 给模型的 JSON 提示
     */
    private String awaitCartSelection(Long addressId, Long userCouponId) {
        CartSelection selection;
        try {
            selection = proposalService.prepareCartSelection(userId, addressId, userCouponId);
        } catch (BusinessException e) {
            return "{\"error\":\"ADDRESS_INVALID\",\"hint\":" + ToolJson.str(e.getMsg()) + "}";
        } catch (RuntimeException e) {
            return "{\"error\":\"CART_SELECTION_FAILED\",\"hint\":\"购物车读取失败，请稍后再试\"}";
        }
        if (selection == null || selection.items() == null || selection.items().isEmpty()) {
            return "{\"error\":\"CART_EMPTY\",\"hint\":\"购物车是空的，先用 search_products 找商品并 add_to_cart 加购\"}";
        }
        log.info("工具结果 propose_order userId={} status=NEED_CART_SELECTION addressId={} cartItems={} totalNumber={}",
                userId, selection.addressId(), selection.items().size(), selection.totalNumber());
        try {
            events.onCartSelection(selection);
        } catch (RuntimeException e) {
            return "{\"error\":\"CART_SELECTION_FAILED\",\"hint\":\"购物车弹层下发失败，请提示用户去购物车页操作\"}";
        }
        return "{\"status\":\"NEED_CART_SELECTION\","
                + "\"itemCount\":" + selection.items().size()
                + ",\"hint\":\"已在对话内弹出购物车，请让用户勾选商品并点击『生成下单提议』，"
                + "本轮不要重复调用 propose_order，也不要再列商品清单\"}";
    }

    /**
     * 下发提议生成事件。失败只影响卡片展示，不影响工具返回值。
     *
     * @param proposal 提议视图
     */
    private void pushProposal(ProposalVO proposal) {
        try {
            events.onProposal(proposal);
        } catch (RuntimeException e) {
            // 事件下发失败不影响工具结果：用户仍可在后续查询里看到提议
        }
    }

    /**
     * 提议视图转 JSON。金额用 plainString，避免科学计数法。
     * <p>
     * 特意把 {@code items} 带上：模型要向用户复述「本次包含哪几件」，
     * 如果不给清单，它只能靠上一轮的 list_my_cart 结果回忆，
     * 而那个结果可能已被新一轮勾选改变——这正是「说错商品、漏说或多说」的来源。
     * 同时附一句 hint，把「不许增删商品」钉在返回值里，而不是只写在 prompt 里。
     *
     * @param proposal 提议
     * @return JSON 字符串
     */
    private static String toJson(ProposalVO proposal) {
        List<ProposalVO.ItemVO> items = proposal.items() == null ? List.of() : proposal.items();
        StringBuilder sb = new StringBuilder(320);
        sb.append("{\"proposalNo\":").append(ToolJson.str(proposal.proposalNo()))
                .append(",\"status\":").append(ToolJson.str(proposal.status()))
                .append(",\"actualPrice\":").append(ToolJson.num(proposal.actualPrice()))
                .append(",\"goodsPrice\":").append(ToolJson.num(proposal.goodsPrice()))
                .append(",\"freightPrice\":").append(ToolJson.num(proposal.freightPrice()))
                .append(",\"couponPrice\":").append(ToolJson.num(proposal.couponPrice()))
                .append(",\"expireAt\":").append(ToolJson.str(proposal.expireAt()))
                .append(",\"itemCount\":").append(items.size())
                .append(",\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            ProposalVO.ItemVO item = items.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":").append(ToolJson.str(item.goodsName()))
                    .append(",\"number\":").append(ToolJson.num(item.number()))
                    .append(",\"unitPrice\":").append(ToolJson.num(item.unitPrice()))
                    .append('}');
        }
        sb.append("],\"message\":").append(ToolJson.str(proposal.message()))
                .append(",\"hint\":").append(ToolJson.str(CONFIRM_HINT))
                .append('}');
        return sb.toString();
    }
}
