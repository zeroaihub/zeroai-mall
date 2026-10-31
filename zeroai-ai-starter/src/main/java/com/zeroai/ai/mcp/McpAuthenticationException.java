package com.zeroai.ai.mcp;

/**
 * MCP 认证失败。
 * <p>
 * reason 只用于服务端日志与测试，不允许原样返回给客户端，避免泄露验签细节。
 */
public class McpAuthenticationException extends RuntimeException {

    private final String reason;

    /**
     * @param reason 内部失败原因码
     */
    public McpAuthenticationException(String reason) {
        super(reason);
        this.reason = reason;
    }

    /**
     * @return 内部失败原因码
     */
    public String getReason() {
        return reason;
    }
}
