package com.zeroai.ai.mall.tool;

import com.zeroai.ai.mall.event.CartSelection;
import com.zeroai.ai.mall.event.GuideUiEvents;
import com.zeroai.ai.mall.proposal.ActionProposalService;
import com.zeroai.ai.mall.proposal.ProposalRejectCode;
import com.zeroai.ai.mall.proposal.ProposalValidationException;
import com.zeroai.util.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单前「勾选弹层」工具的行为测试。
 * <p>
 * 这个工具现在只有一个职责：把购物车弹给用户，由用户确认商品与数量。
 * 因此断言集中在三点：<b>永远不建提议</b>、<b>cartIds 能限定弹层内容</b>、
 * <b>各种失败都给出模型可据以引导用户的稳定错误码</b>。
 */
class ProposeOrderToolTest {

    private static CartSelection selection(long cartId, String name) {
        return new CartSelection(9L, "张三 138****8888 浙江省 杭州市", null,
                List.of(new CartSelection.Item(cartId, 2L, 3L, name,
                        new BigDecimal("20.00"), 1, true, "http://img")), 1);
    }

    @Test
    @DisplayName("未登录用户不允许创建提议工具")
    void rejectsNullUserId() {
        ActionProposalService service = mock(ActionProposalService.class);
        assertThrows(IllegalArgumentException.class, () -> new ProposeOrderTool(service, null, null));
    }

    @Test
    @DisplayName("缺地址时返回可理解错误，不写任何数据")
    void missingAddressReturnsError() {
        ActionProposalService service = mock(ActionProposalService.class);
        when(service.prepareCartSelection(eq(100L), any(), any(), any()))
                .thenThrow(new BusinessException("还没有收货地址，请先在「我的-收货地址」添加"));

        String result = new ProposeOrderTool(service, 100L, null).proposeOrder(null, null, (String) null);

        assertTrue(result.contains("ADDRESS_INVALID"), result);
        assertTrue(result.contains("收货地址"), result);
    }

    @Test
    @DisplayName("购物车为空时不弹层，返回可操作提示")
    void emptyCartReturnsHint() {
        ActionProposalService service = mock(ActionProposalService.class);
        when(service.prepareCartSelection(eq(100L), any(), any(), any())).thenReturn(null);

        String result = new ProposeOrderTool(service, 100L, null).proposeOrder(null, null, (String) null);

        assertTrue(result.contains("CART_EMPTY"), result);
    }

    @Test
    @DisplayName("不传 cartIds 时展示整份购物车")
    void withoutCartIdsShowsWholeCart() {
        ActionProposalService service = mock(ActionProposalService.class);
        // 字符串入参解析后是空列表（不是 null），stub 必须按空列表匹配
        when(service.prepareCartSelection(eq(100L), any(), any(), eq(List.of())))
                .thenReturn(selection(1L, "测试商品"));
        AtomicReference<CartSelection> pushed = new AtomicReference<>();
        GuideUiEvents events = new GuideUiEvents() {
            @Override
            public void onCartSelection(CartSelection value) {
                pushed.set(value);
            }
        };

        String result = new ProposeOrderTool(service, 100L, events).proposeOrder(null, null, (String) null);

        assertTrue(result.contains("NEED_CART_SELECTION"), result);
        assertTrue(result.contains("不要重复调用"), "必须明确阻止模型重复调用，否则会连着弹两次");
        assertNotNull(pushed.get(), "购物车必须下发给前端，否则用户没地方勾选");
    }

    @Test
    @DisplayName("传 cartIds 时把 ID 原样交给服务端，弹层只含用户要下的商品")
    void cartIdsScopeTheSheet() {
        ActionProposalService service = mock(ActionProposalService.class);
        when(service.prepareCartSelection(eq(100L), any(), any(), eq(List.of(20L, 21L))))
                .thenReturn(selection(20L, "小米手机"));

        String result = new ProposeOrderTool(service, 100L, GuideUiEvents.NOOP)
                .proposeOrder(null, null, List.of(20L, 21L));

        assertTrue(result.contains("NEED_CART_SELECTION"), result);
        // 关键点：模型挑的商品必须传到服务端，否则弹层又会倒出整份购物车
        verify(service).prepareCartSelection(eq(100L), any(), any(), eq(List.of(20L, 21L)));
    }

    @Test
    @DisplayName("工具入口接收逗号分隔字符串，避免集合参数在绑定阶段失败")
    void stringEntrypointParsesCartIds() {
        ActionProposalService service = mock(ActionProposalService.class);
        when(service.prepareCartSelection(eq(100L), any(), any(), eq(List.of(25L, 26L))))
                .thenReturn(selection(25L, "小米手机"));

        String result = new ProposeOrderTool(service, 100L, GuideUiEvents.NOOP)
                .proposeOrder(null, null, "25,26");

        assertTrue(result.contains("NEED_CART_SELECTION"), result);
        verify(service).prepareCartSelection(eq(100L), any(), any(), eq(List.of(25L, 26L)));
    }

    @Test
    @DisplayName("服务端校验失败时回传稳定错误码与明细")
    void validationFailureReturnsActionableCode() {
        ActionProposalService service = mock(ActionProposalService.class);
        when(service.prepareCartSelection(any(), any(), any(), any()))
                .thenThrow(new ProposalValidationException(ProposalRejectCode.CART_CHANGED,
                        "部分商品不在购物车中或已被删除"));

        String result = new ProposeOrderTool(service, 100L, GuideUiEvents.NOOP)
                .proposeOrder(null, null, List.of(10L));

        assertTrue(result.contains("\"error\":\"CART_CHANGED\""), result);
        assertTrue(result.contains("已被删除"), result);
        assertFalse(result.contains("CART_SELECTION_FAILED"), result);
    }

    @Test
    @DisplayName("弹层下发失败时明确告知，让模型引导用户去购物车页")
    void eventFailureIsReported() {
        ActionProposalService service = mock(ActionProposalService.class);
        when(service.prepareCartSelection(any(), any(), any(), any())).thenReturn(selection(1L, "测试商品"));
        GuideUiEvents broken = new GuideUiEvents() {
            @Override
            public void onCartSelection(CartSelection value) {
                throw new IllegalStateException("sse closed");
            }
        };

        String result = new ProposeOrderTool(service, 100L, broken).proposeOrder(null, null, (String) null);

        assertTrue(result.contains("CART_SELECTION_FAILED"), result);
        assertFalse(result.contains("sse closed"), "内部异常信息不得回显给模型");
    }

    @Test
    @DisplayName("未知异常只给重试提示，不泄露内部信息")
    void systemFailureHidesInternals() {
        ActionProposalService service = mock(ActionProposalService.class);
        when(service.prepareCartSelection(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        String result = new ProposeOrderTool(service, 100L, null).proposeOrder(null, null, (String) null);

        assertTrue(result.contains("CART_SELECTION_FAILED"), result);
        assertFalse(result.contains("db down"), result);
        assertEquals(false, result.contains("at com."));
    }
}
