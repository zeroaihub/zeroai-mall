package com.zeroai.ai.mcp;

import com.zeroai.ai.mall.port.MallSearchPort;
import com.zeroai.domain.api.trade.service.IMobileOrderService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 工具面与用户隔离测试。
 */
class McpMallToolsTest {

    @Test
    @DisplayName("MCP 只暴露四个显式只读工具，schema 不含 userId 或 ToolContext")
    void shouldExposeOnlyReadOnlyToolsWithoutUserParameter() {
        McpMallTools tools = tools(null, mock(IMobileOrderService.class));
        ToolCallback[] callbacks = McpToolCallbacks.create(tools, JsonMapper.builder().build())
                .toArray(ToolCallback[]::new);

        Map<String, ToolCallback> byName = Arrays.stream(callbacks)
                .collect(Collectors.toMap(callback -> callback.getToolDefinition().name(), callback -> callback));
        assertEquals(Set.of(McpToolNames.SEARCH_PRODUCTS, McpToolNames.GET_PRODUCT_DETAIL,
                McpToolNames.SEARCH_KNOWLEDGE, McpToolNames.LIST_MY_ORDERS), byName.keySet());

        for (ToolCallback callback : callbacks) {
            String schema = callback.getToolDefinition().inputSchema();
            assertFalse(schema.contains("\"userId\""), "用户 ID 不能成为模型参数：" + schema);
            assertFalse(schema.contains("ToolContext"), "服务端上下文不能进入工具 schema：" + schema);
        }
        assertFalse(byName.keySet().stream().anyMatch(name ->
                Set.of("propose_order", "create_order", "pay_order", "refund_order",
                        "cancel_order", "deduct_stock", "update_price").contains(name)));
    }

    @Test
    @DisplayName("非法工具参数返回稳定错误且不进入业务查询")
    void shouldRejectInvalidArgumentsBeforeBusinessCall() {
        IMobileOrderService orders = mock(IMobileOrderService.class);
        ToolCallback search = McpToolCallbacks.create(tools(null, orders), JsonMapper.builder().build())
                .stream()
                .filter(callback -> McpToolNames.SEARCH_PRODUCTS
                        .equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();

        String json = search.call("{\"keyword\":1}", authContext(3003L));

        assertTrue(json.contains("INVALID_ARGUMENTS"), "实际=" + json);
        verify(orders, org.mockito.Mockito.never())
                .selectListPage(any(), any(Integer.class), any(Long.class));
    }

    @Test
    @DisplayName("未声明字段和越界分页不会进入业务查询")
    void shouldRejectExtraFieldsAndOutOfRangeLimit() {
        IMobileOrderService orders = mock(IMobileOrderService.class);
        ToolCallback listOrders = McpToolCallbacks.create(tools(null, orders), JsonMapper.builder().build())
                .stream()
                .filter(callback -> McpToolNames.LIST_MY_ORDERS
                        .equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();

        String extraField = listOrders.call("{\"userId\":3004}", authContext(3003L));
        String hugeLimit = listOrders.call("{\"limit\":100}", authContext(3003L));

        assertTrue(extraField.contains("INVALID_ARGUMENTS"), "实际=" + extraField);
        assertTrue(hugeLimit.contains("INVALID_ARGUMENTS"), "实际=" + hugeLimit);
        verify(orders, org.mockito.Mockito.never())
                .selectListPage(any(), any(Integer.class), any(Long.class));
    }

    @Test
    @DisplayName("缺少 MCP transport identity 时订单工具拒绝执行")
    void shouldRejectOrderQueryWithoutIdentity() {
        IMobileOrderService orders = mock(IMobileOrderService.class);
        String json = tools(null, orders).listMyOrders(3, null);

        assertTrue(json.contains("AUTH_REQUIRED"), "实际=" + json);
        verify(orders, org.mockito.Mockito.never()).selectListPage(any(), any(Integer.class), any(Long.class));
    }

    @Test
    @DisplayName("订单工具只使用 token 中的 userId 查询当前用户")
    void shouldUseAuthenticatedUserForOrderQuery() {
        IMobileOrderService orders = mock(IMobileOrderService.class);
        when(orders.selectListPage(any(), eq(0), eq(3003L))).thenReturn(null);

        String json = tools(null, orders).listMyOrders(3, authContext(3003L));

        assertEquals("{\"count\":0,\"orders\":[]}", json);
        verify(orders).selectListPage(any(), eq(0), eq(3003L));
    }

    @Test
    @DisplayName("商品搜索继续复用现有只读工具链路")
    void shouldDelegateProductSearchToExistingTool() {
        MallSearchPort port = (keyword, limit) -> List.of(
                new MallSearchPort.GoodsBrief(9L, "测试商品", new BigDecimal("19.90"), 2, null));

        String json = tools(port, mock(IMobileOrderService.class))
                .searchProducts("测试", 5, authContext(3003L));

        assertTrue(json.contains("\"count\":1"), "实际=" + json);
        assertTrue(json.contains("测试商品"), "实际=" + json);
    }

    /**
     * 创建工具适配层。
     *
     * @param port   检索端口
     * @param orders 订单服务
     * @return MCP 工具
     */
    private static McpMallTools tools(MallSearchPort port, IMobileOrderService orders) {
        return new McpMallTools(port, null, null, orders, new McpPrincipalResolver());
    }

    /**
     * 构造带已验证身份的 MCP ToolContext。
     *
     * @param userId 登录用户 ID
     * @return 工具上下文
     */
    private static ToolContext authContext(long userId) {
        McpPrincipal principal = new McpPrincipal(userId, "test-client",
                1_800_000_000L, 1_800_000_900L);
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(
                McpTransportContext.create(Map.of(McpPrincipal.CONTEXT_KEY, principal)));
        return new ToolContext(Map.of("exchange", exchange));
    }
}
