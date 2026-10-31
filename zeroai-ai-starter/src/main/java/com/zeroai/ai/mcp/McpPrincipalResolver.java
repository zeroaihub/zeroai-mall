package com.zeroai.ai.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

/**
 * 从 MCP transport context 中解析当前调用方。
 * <p>
 * 不允许工具方法接收 userId 参数。身份必须先由 HTTP 过滤器验签，再经
 * {@code McpTransportContextExtractor} 写入每次工具调用的上下文。
 */
public final class McpPrincipalResolver {

    /**
     * 读取并强制校验当前调用方身份。
     *
     * @param toolContext Spring AI 注入的工具上下文
     * @return 验签后的 MCP 身份
     * @throws McpAuthenticationException 工具调用上下文缺失身份
     */
    public McpPrincipal require(ToolContext toolContext) {
        McpTransportContext transportContext = McpToolUtils.getMcpExchange(toolContext)
                .map(exchange -> exchange.transportContext())
                .orElse(null);
        Object value = transportContext == null ? null : transportContext.get(McpPrincipal.CONTEXT_KEY);
        if (value instanceof McpPrincipal principal) {
            return principal;
        }
        throw new McpAuthenticationException("AUTH_CONTEXT_MISSING");
    }
}
