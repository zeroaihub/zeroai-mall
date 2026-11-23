package com.zeroai.ai.mall.event;

import com.zeroai.ai.mall.proposal.vo.ProposalVO;

/**
 * 工具执行过程中的「对外事件」出口。
 * <p>
 * 为什么单独抽一个接口：工具实例（搜索、加购、下单提议）只知道自己产生了什么结果，
 * 不应该知道 SSE、事件名、序列化格式；下一层由 {@code AgentChatHandler} 负责转成 SSE 事件。
 * 这样工具在单测里可以直接传一个记录用的实现，不必启动 Web 容器。
 * <p>
 * 所有方法都有空实现：工具在不需要推送（如单测、MCP 复用）时传 {@link #NOOP} 即可，
 * 不必到处判空。实现方必须保证方法不抛异常，否则会打断工具执行。
 */
public interface GuideUiEvents {

    /** 空实现，供不关心推送的调用方使用。 */
    GuideUiEvents NOOP = new GuideUiEvents() {
    };

    /**
     * 搜索命中商品，前端渲染商品卡片。
     *
     * @param cards 商品卡片载荷；实现方需容忍 null
     */
    default void onProductCards(ProductCards cards) {
    }

    /**
     * 加购成功，前端把对应卡片标记为已加入。
     *
     * @param added 加购结果；实现方需容忍 null
     */
    default void onCartAdded(CartAdded added) {
    }

    /**
     * 需要用户挑选下单商品，前端弹出购物车勾选层。
     *
     * @param selection 购物车与地址快照；实现方需容忍 null
     */
    default void onCartSelection(CartSelection selection) {
    }

    /**
     * 已生成待确认提议，前端渲染确认卡片。
     *
     * @param proposal 提议视图；实现方需容忍 null
     */
    default void onProposal(ProposalVO proposal) {
    }
}
