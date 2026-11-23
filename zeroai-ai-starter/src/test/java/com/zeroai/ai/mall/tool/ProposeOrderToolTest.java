package com.zeroai.ai.mall.tool;

import com.zeroai.ai.mall.event.CartSelection;
import com.zeroai.ai.mall.event.GuideUiEvents;
import com.zeroai.ai.mall.proposal.ActionProposalService;
import com.zeroai.ai.mall.proposal.ProposalRejectCode;
import com.zeroai.ai.mall.proposal.ProposalValidationException;
import com.zeroai.ai.mall.proposal.vo.ProposalVO;
import com.zeroai.util.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单提议工具的边界测试。
 * <p>
 * 重点不是「能生成 JSON」，而是四条行为约束：
 * 未登录不许建工具、不点名商品时只弹购物车不建提议、生成后必须回调下发 confirm_action、
 * 各种失败都返回模型能据以引导用户的稳定错误码。
 */
class ProposeOrderToolTest {

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
        when(service.prepareCartSelection(eq(100L), any(), any()))
                .thenThrow(new BusinessException("还没有收货地址，请先在「我的-收货地址」添加"));
        ProposeOrderTool tool = new ProposeOrderTool(service, 100L, null);

        String result = tool.proposeOrder(null, null, List.of());

        assertTrue(result.contains("ADDRESS_INVALID"), result);
        assertTrue(result.contains("收货地址"), result);
        verify(service, never()).createOrderProposal(any(), any(), any(), any());
    }

    @Test
    @DisplayName("购物车为空时不弹层，返回可操作提示")
    void emptyCartReturnsHint() {
        ActionProposalService service = mock(ActionProposalService.class);
        when(service.prepareCartSelection(eq(100L), any(), any())).thenReturn(null);
        ProposeOrderTool tool = new ProposeOrderTool(service, 100L, null);

        String result = tool.proposeOrder(null, null, List.of());

        assertTrue(result.contains("CART_EMPTY"), result);
        verify(service, never()).createOrderProposal(any(), any(), any(), any());
    }

    @Test
    @DisplayName("未点名商品时不直接建提议，改为下发购物车勾选事件")
    void emptySelectionAsksUserToPickCartItems() {
        ActionProposalService service = mock(ActionProposalService.class);
        CartSelection selection = new CartSelection(9L, "张三 138****8888 浙江省 杭州市", null,
                List.of(new CartSelection.Item(1L, 2L, 3L, "测试商品",
                        new BigDecimal("20.00"), 1, true, "http://img")), 1);
        when(service.prepareCartSelection(eq(100L), any(), any())).thenReturn(selection);

        AtomicReference<CartSelection> pushed = new AtomicReference<>();
        GuideUiEvents events = new GuideUiEvents() {
            @Override
            public void onCartSelection(CartSelection value) {
                pushed.set(value);
            }
        };
        String result = new ProposeOrderTool(service, 100L, events).proposeOrder(null, null, List.of());

        assertTrue(result.contains("NEED_CART_SELECTION"), result);
        assertTrue(result.contains("不要重复调用"), "必须明确阻止模型重复调用，否则她会连着弹两次");
        assertNotNull(pushed.get(), "购物车必须下发给前端，否则用户没地方勾选");
        verify(service, never()).createOrderProposal(any(), any(), any(), any());
    }

    @Test
    @DisplayName("生成成功后回调监听器并返回提议编号")
    void successNotifiesListener() {
        ActionProposalService service = mock(ActionProposalService.class);
        ProposalVO vo = new ProposalVO("p-1", "PENDING", "CREATE_ORDER",
                new BigDecimal("50.00"), new BigDecimal("40.00"), new BigDecimal("10.00"),
                BigDecimal.ZERO, "2026-10-26T17:00:00", List.of(),
                null, null, "下单提议已生成，需要你确认后才会创建订单");
        when(service.createOrderProposal(eq(100L), eq(1L), any(), anyList())).thenReturn(vo);

        AtomicReference<ProposalVO> captured = new AtomicReference<>();
        GuideUiEvents events = new GuideUiEvents() {
            @Override
            public void onProposal(ProposalVO proposal) {
                captured.set(proposal);
            }
        };
        String result = new ProposeOrderTool(service, 100L, events).proposeOrder(1L, null, List.of(10L));

        assertTrue(result.contains("\"proposalNo\":\"p-1\""), result);
        assertTrue(result.contains("需要你确认"), result);
        assertEquals("p-1", captured.get().proposalNo());
    }

    @Test
    @DisplayName("业务拒绝时返回可理解文案，不泄露堆栈")
    void businessRejectionReturnsReadableHint() {
        ActionProposalService service = mock(ActionProposalService.class);
        when(service.createOrderProposal(any(), any(), any(), any()))
                .thenThrow(new BusinessException("收货地址无效，请重新选择"));
        ProposeOrderTool tool = new ProposeOrderTool(service, 100L, null);

        String result = tool.proposeOrder(1L, null, List.of(10L));

        assertTrue(result.contains("PROPOSAL_REJECTED"), result);
        assertTrue(result.contains("收货地址无效"), result);
        assertFalse(result.contains("at com."), result);
    }

    @Test
    @DisplayName("系统异常时对外只给重试提示")
    void systemFailureReturnsRetryHint() {
        ActionProposalService service = mock(ActionProposalService.class);
        when(service.createOrderProposal(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));
        ProposeOrderTool tool = new ProposeOrderTool(service, 100L, null);

        String result = tool.proposeOrder(1L, null, List.of(10L));

        assertTrue(result.contains("PROPOSAL_FAILED"), result);
        assertFalse(result.contains("db down"), result);
    }

    @Test
    @DisplayName("未勾选商品被拒绝时回传稳定错误码与明细，供模型引导用户勾选")
    void notCheckedReturnsActionableCode() {
        ActionProposalService service = mock(ActionProposalService.class);
        when(service.createOrderProposal(any(), any(), any(), any()))
                .thenThrow(new ProposalValidationException(ProposalRejectCode.CART_NOT_CHECKED,
                        "未勾选：iPhone 保护壳"));
        ProposeOrderTool tool = new ProposeOrderTool(service, 100L, null);

        String result = tool.proposeOrder(1L, null, List.of(10L));

        assertTrue(result.contains("\"error\":\"CART_NOT_CHECKED\""), result);
        assertTrue(result.contains("勾选"), result);
        assertTrue(result.contains("保护壳"), "明细要带回具体商品名，模型才能让用户去勾选");
        assertFalse(result.contains("PROPOSAL_FAILED"), result);
    }

    @Test
    @DisplayName("成功时返回商品清单与「不许增删」提示")
    void successCarriesItemList() {
        ActionProposalService service = mock(ActionProposalService.class);
        ProposalVO vo = new ProposalVO("p-2", "PENDING", "CREATE_ORDER",
                new BigDecimal("50.00"), new BigDecimal("40.00"), new BigDecimal("10.00"),
                BigDecimal.ZERO, "2026-10-26T17:00:00",
                List.of(new ProposalVO.ItemVO("测试商品", new BigDecimal("20.00"), 2)),
                null, null, "下单提议已生成，需要你确认后才会创建订单");
        when(service.createOrderProposal(eq(100L), eq(1L), any(), anyList())).thenReturn(vo);

        AtomicInteger pushes = new AtomicInteger();
        GuideUiEvents events = new GuideUiEvents() {
            @Override
            public void onProposal(ProposalVO proposal) {
                pushes.incrementAndGet();
            }
        };
        String result = new ProposeOrderTool(service, 100L, events).proposeOrder(1L, null, List.of(10L));

        assertTrue(result.contains("\"itemCount\":1"), result);
        assertTrue(result.contains("测试商品"), result);
        assertTrue(result.contains("\"number\":2"), result);
        assertTrue(result.contains("不要自行增加或替换"), result);
        assertEquals(1, pushes.get(), "提议卡片只应下发一次");
    }
}
