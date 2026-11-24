package com.zeroai.ai.engine.langchain4j.tool;

import com.zeroai.ai.mall.event.CartSelection;
import com.zeroai.ai.mall.event.GuideUiEvents;
import com.zeroai.ai.mall.proposal.ActionProposalService;
import com.zeroai.ai.mall.proposal.ProposalValidationException;
import com.zeroai.util.exception.BusinessException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提议工具：准备下单（LangChain4j 版）。
 * <p>
 * 行为与 {@code com.zeroai.ai.mall.tool.ProposeOrderTool}（AgentScope 版）完全一致：
 * <b>不建提议</b>，只把购物车弹到前端弹层（cartIds 只限定弹层内容），
 * 用户在弹层里选商品、改数量、确认地址后，前端走独立接口生成提议。
 * 模型永远不能替用户按下确认键。userId 构造期注入。
 * <b>两边方法体必须同步修改</b>，见 ADR-003。
 */
public class ProposeOrderTool {

    private static final Logger log = LoggerFactory.getLogger(ProposeOrderTool.class);

    private final ActionProposalService proposalService;
    private final Long userId;
    private final GuideUiEvents events;

    /**
     * @param proposalService 提议服务（业务层共享，见 ADR-003）
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
     * 准备下单：把「这次要下单的商品」弹给用户确认，不创建订单。
     *
     * @param addressId    收货地址 ID，来自 list_my_addresses；为空时服务端取默认地址
     * @param userCouponId 用户优惠券 ID，可不填
     * @param cartIds      本次要下单的购物车 ID（来自 list_my_cart）；不填表示展示整份购物车
     * @return JSON 字符串：等待勾选时含 NEED_CART_SELECTION 状态
     */
    @Tool(name = "propose_order",
            value = {"准备下单：弹出购物车让用户确认商品和数量，不会创建订单。",
                    "cartIds 用逗号分隔传本次要下单的购物车ID（先 list_my_cart），如 25,26；说「全部」才不传。",
                    "addressId 为空取默认地址。"})
    public String proposeOrder(@P(value = "收货地址ID，来自 list_my_addresses，可空", required = false) Long addressId,
                               @P(value = "用户优惠券ID，可空", required = false) Long userCouponId,
                               @P(value = "本次要下单的购物车ID，逗号分隔，如 25,26；说「全部」才不传",
                                       required = false) String cartIds) {
        // ID 列表走字符串 + 解析器：与 AgentScope 版行为完全对齐
        return proposeOrder(addressId, userCouponId, IdListParser.parse(cartIds));
    }

    /**
     * 按购物车 ID 弹层（内部入口，单测可直接调）。
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
     * 购物车为空属于「可预期的用户态」，返回结构化提示让模型引导用户；
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
            return "{\"error\":" + JsonText.str(e.getCode().name())
                    + ",\"hint\":" + JsonText.str(e.getDetail().isBlank()
                    ? e.getCode().getMessage() : e.getDetail()) + "}";
        } catch (BusinessException e) {
            return "{\"error\":\"ADDRESS_INVALID\",\"hint\":" + JsonText.str(e.getMsg()) + "}";
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
