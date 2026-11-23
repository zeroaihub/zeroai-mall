package com.zeroai.ai.mall.tool;

import com.zeroai.ai.mall.event.CartSelection;
import com.zeroai.ai.mall.event.GuideUiEvents;
import com.zeroai.ai.mall.proposal.ActionProposalService;
import com.zeroai.ai.mall.proposal.ProposalValidationException;
import com.zeroai.util.exception.BusinessException;
import io.agentscope.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提议工具：生成「创建订单」待确认提议。
 * <p>
 * <b>这是 Agent 唯一具备「写」性质的工具，但它只写提议表，不创建订单。</b>
 * 订单写入只能由用户通过独立确认接口触发（见 {@code ActionProposalService#confirm}）。
 * 把「提议」和「执行」拆成两个入口，正是 Phase 6 Human-in-the-loop 的核心：
 * 模型可以准备动作，但永远不能替用户按下确认键。
 * <p>
 * 本工具<b>不建提议</b>，只负责把购物车弹到前端：用户在弹层里选商品、改数量并确认后，
 * 前端走独立接口 {@code POST /ai/action/proposal} 生成提议。这样「买什么、买几件」
 * 始终由用户自己按下确认，模型最多决定「弹层里先摆哪几件」（cartIds）。
 * <p>
 * userId 在构造时由服务端注入，模型无法指定他人身份。
 */
public class ProposeOrderTool {

    private static final Logger log = LoggerFactory.getLogger(ProposeOrderTool.class);

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
     * 准备下单：把「这次要下单的商品」弹给用户勾选并确认，不创建订单。
     * <p>
     * <b>为什么不再区分「传 cartIds 就直接建提议」</b>：那样用户从没看见金额与清单，
     * 只是模型单方面决定了买什么；而勾选弹层正是让他确认这件商品、这个数量的一步。
     * 现在 cartIds 只用于<b>限定弹层内容</b>，是否下单始终由用户在弹层里确认。
     *
     * @param addressId    收货地址 ID，来自 list_my_addresses；为空时服务端取默认地址
     * @param userCouponId 用户优惠券 ID，可不填
     * @param cartIds      本次要下单的购物车 ID（来自 list_my_cart）；不填表示展示整份购物车
     * @return JSON 字符串：等待勾选时含 NEED_CART_SELECTION 状态
     */
    @Tool(name = "propose_order",
            description = "准备下单：弹出购物车让用户确认商品和数量，不会创建订单。"
                    + "cartIds 用逗号分隔传本次要下单的购物车ID（先 list_my_cart），"
                    + "如 25,26；说「全部」才不传。addressId 为空取默认地址。",
            readOnly = false,
            concurrencySafe = false)
    public String proposeOrder(Long addressId, Long userCouponId, String cartIds) {
        // 与 show_products 同一处理：集合类型参数会在 SDK 绑定阶段失败，
        // 改成字符串后自己解析，模型写成 25,26 或 [25,26] 都能用
        return proposeOrder(addressId, userCouponId, IdListParser.parse(cartIds));
    }

    /**
     * 按购物车 ID 弹层（内部入口，单测与 MCP 可直接调）。
     *
     * @param addressId    收货地址 ID，可为空
     * @param userCouponId 优惠券 ID，可为空
     * @param cartIds      限定弹层内容的购物车 ID，可为空
     * @return 给模型的 JSON 提示
     */
    public String proposeOrder(Long addressId, Long userCouponId, java.util.List<Long> cartIds) {
        log.info("工具调用 propose_order userId={} addressId={} couponId={} cartIds={}",
                userId, addressId, userCouponId, cartIds);
        return awaitCartSelection(addressId, userCouponId, cartIds);
    }

    /**
     * 把购物车下发到前端弹层，等待用户勾选。
     * <p>
     * 未登录、购物车为空属于「可预期的用户态」，返回结构化提示让模型引导用户，
     * 而不是抛异常——抛异常会终止整轮回答，用户只看到一句「服务繁忙」。
     * 没有收货地址不再算失败：弹层会弹出来让用户选或新增，提交前必须选一个。
     *
     * @param addressId    收货地址 ID，可为空
     * @param userCouponId 优惠券 ID，可为空
     * @param cartIds      限定弹层内容的购物车 ID，可为空
     * @return 给模型的 JSON 提示
     */
    private String awaitCartSelection(Long addressId, Long userCouponId, java.util.List<Long> cartIds) {
        CartSelection selection;
        try {
            selection = proposalService.prepareCartSelection(userId, addressId, userCouponId, cartIds);
        } catch (ProposalValidationException e) {
            // 携带稳定错误码，模型据此纠正参数（如购物车行已被删除），而不是反复重试
            return "{\"error\":" + ToolJson.str(e.getCode().name())
                    + ",\"hint\":" + ToolJson.str(e.getDetail().isBlank()
                    ? e.getCode().getMessage() : e.getDetail()) + "}";
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
        // 地址为空不是失败：弹层里会让用户选或新增地址，但模型别再自己编一个地址出来
        String addressHint = selection.addressId() == null
                ? "用户还没有收货地址，请让他在弹层里选择或新增，不要自己编造地址"
                : "让用户在弹层里核对收货地址、商品和数量";
        return "{\"status\":\"NEED_CART_SELECTION\","
                + "\"itemCount\":" + selection.items().size()
                + ",\"addressMissing\":" + (selection.addressId() == null)
                + ",\"hint\":\"已在对话内弹出购物车，" + addressHint + "，然后点『生成下单提议』，"
                + "本轮不要重复调用 propose_order，也不要再列商品清单\"}";
    }
}
