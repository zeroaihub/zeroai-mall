package com.zeroai.ai.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 短期 token 格式与安全边界测试。
 */
class McpTokenCodecTest {

    private static final String SECRET = "test-mcp-secret-0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");

    @Test
    @DisplayName("有效 token 能还原用户、客户端与有效期")
    void shouldVerifyIssuedToken() {
        McpTokenCodec codec = codec(NOW);
        String token = codec.issue(1001L, "cursor-local", Duration.ofMinutes(15));

        McpPrincipal principal = codec.verify(token);

        assertEquals(1001L, principal.userId());
        assertEquals("cursor-local", principal.clientId());
        assertEquals(NOW.getEpochSecond() + 900, principal.expiresAt());
    }

    @Test
    @DisplayName("篡改任意签名字符必须失败")
    void shouldRejectTamperedSignature() {
        McpTokenCodec codec = codec(NOW);
        String token = codec.issue(1001L, "inspector", Duration.ofMinutes(15));
        int signatureStart = token.lastIndexOf('.') + 1;
        char oldChar = token.charAt(signatureStart);
        String tampered = token.substring(0, signatureStart)
                + (oldChar == 'A' ? 'B' : 'A')
                + token.substring(signatureStart + 1);

        McpAuthenticationException error =
                assertThrows(McpAuthenticationException.class, () -> codec.verify(tampered));
        assertEquals("INVALID_SIGNATURE", error.getReason());
    }

    @Test
    @DisplayName("超过有效期必须失败")
    void shouldRejectExpiredToken() {
        String token = codec(NOW).issue(1001L, "agent-a", Duration.ofMinutes(10));
        McpTokenCodec later = codec(NOW.plus(Duration.ofMinutes(12)));

        McpAuthenticationException error =
                assertThrows(McpAuthenticationException.class, () -> later.verify(token));
        assertEquals("TOKEN_EXPIRED", error.getReason());
    }

    @Test
    @DisplayName("签发方不得生成超过配置上限的 token")
    void shouldRejectTtlAboveLimit() {
        McpTokenCodec codec = codec(NOW);

        assertThrows(IllegalArgumentException.class,
                () -> codec.issue(1001L, "agent-a", Duration.ofHours(2)));
    }

    @Test
    @DisplayName("弱密钥不得启动 MCP 认证")
    void shouldRejectShortSecret() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new McpTokenCodec("too-short", Duration.ofHours(1), Duration.ZERO));
        assertTrue(error.getMessage().contains("MCP_TOKEN_SECRET"));
    }

    @Test
    @DisplayName("缺失 token 返回稳定内部原因")
    void shouldRejectMissingToken() {
        McpAuthenticationException error =
                assertThrows(McpAuthenticationException.class, () -> codec(NOW).verify(" "));
        assertEquals("MISSING_TOKEN", error.getReason());
    }

    /**
     * 创建固定时钟的 token 编解码器。
     *
     * @param now 固定时间
     * @return 编解码器
     */
    private static McpTokenCodec codec(Instant now) {
        return new McpTokenCodec(SECRET, Duration.ofHours(1), Duration.ofSeconds(60),
                Clock.fixed(now, ZoneOffset.UTC));
    }
}
