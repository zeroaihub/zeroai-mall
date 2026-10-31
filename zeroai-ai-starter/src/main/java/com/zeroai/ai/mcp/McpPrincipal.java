package com.zeroai.ai.mcp;

/**
 * MCP 调用方身份快照。
 * <p>
 * 身份只来自服务端验签后的 token。工具参数、MCP session 和客户端自报字段都不能修改它。
 *
 * @param userId    令牌绑定的商城用户 ID
 * @param clientId  调用方标识，用于审计与问题定位
 * @param issuedAt  签发时间（Unix 秒）
 * @param expiresAt 过期时间（Unix 秒）
 */
public record McpPrincipal(long userId, String clientId, long issuedAt, long expiresAt) {

    /** 放入 MCP transport context 的稳定键。 */
    public static final String CONTEXT_KEY = McpPrincipal.class.getName();
}
