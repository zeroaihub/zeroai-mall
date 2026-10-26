package com.zeroai.ai.mall.tool;

import com.zeroai.ai.mall.proposal.ActionProposalService;
import com.zeroai.ai.mall.proposal.vo.ProposalVO;
import com.zeroai.util.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单提议工具的边界测试。
 * <p>
 * 重点不是「能生成 JSON」，而是：
 * 未登录不许建工具、缺地址不许建提议、生成后必须回调下发 confirm_action。
 */
class ProposeOrderToolTest {

    @Test
    @DisplayName("未登录用户不允许创建提议工具")
    void rejectsNullUserId() {
        ActionProposalService service = mock(ActionProposalService.class);
        assertThrows(IllegalArgumentException.class, () -> new ProposeOrderTool(service, null, null));
    }

    @Test
    @DisplayName("缺少地址时返回可理解错误，不写任何数据")
    void missingAddressReturnsError() {
        ActionProposalService service = mock(ActionProposalService.class);
        ProposeOrderTool tool = new ProposeOrderTool(service, 100L, null);

        String result = tool.proposeOrder(null, null, List.of(1L));

        assertTrue(result.contains("MISSING_ADDRESS"), result);
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
        ProposeOrderTool tool = new ProposeOrderTool(service, 100L, captured::set);

        String result = tool.proposeOrder(1L, null, List.of(10L));

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
}
