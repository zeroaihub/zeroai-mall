package com.zeroai.ai.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP HTTP 入口认证测试，覆盖匿名、越权和正常身份注入。
 */
class McpAuthenticationFilterTest {

    private static final String SECRET = "test-mcp-secret-0123456789abcdef";

    @Test
    @DisplayName("本机携带有效 Bearer token 时写入身份并放行")
    void shouldAttachPrincipalForValidToken() throws Exception {
        McpProperties properties = properties(false);
        McpTokenCodec codec = codec();
        String token = codec.issue(2002L, "cursor", Duration.ofMinutes(5));

        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new McpAuthenticationFilter(codec, properties).doFilter(request, response, chain);

        McpPrincipal principal = (McpPrincipal) request.getAttribute(
                McpAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
        assertNotNull(principal);
        assertEquals(2002L, principal.userId());
        assertNotNull(chain.getRequest(), "认证成功后必须继续过滤器链");
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("未携带 token 返回 401 且不进入 MCP handler")
    void shouldRejectMissingToken() throws Exception {
        MockHttpServletRequest request = request("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new McpAuthenticationFilter(codec(), properties(false)).doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("MCP_UNAUTHORIZED"));
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("非回环地址默认拒绝")
    void shouldRejectRemoteAddressByDefault() throws Exception {
        MockHttpServletRequest request = request("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new McpAuthenticationFilter(codec(), properties(false)).doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("MCP_REMOTE_FORBIDDEN"));
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("Bearer 前缀不合法按未认证处理")
    void shouldRejectInvalidBearerPrefix() throws Exception {
        String token = codec().issue(2002L, "cursor", Duration.ofMinutes(5));
        MockHttpServletRequest request = request("::1");
        request.addHeader("Authorization", "Basic " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new McpAuthenticationFilter(codec(), properties(false))
                .doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("移动端 Authorization 归一化后的裸 token 仍可通过 MCP 验签")
    void shouldAcceptBareTokenAfterMobileHeaderNormalization() throws Exception {
        String token = codec().issue(2002L, "cursor", Duration.ofMinutes(5));
        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader("Authorization", token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new McpAuthenticationFilter(codec(), properties(false))
                .doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(request.getAttribute(McpAuthenticationFilter.PRINCIPAL_ATTRIBUTE));
        assertNotNull(chain.getRequest());
    }

    /**
     * 创建 MCP 安全配置。
     *
     * @param allowRemote 是否允许非本机访问
     * @return 配置
     */
    private static McpProperties properties(boolean allowRemote) {
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setTokenSecret(SECRET);
        properties.setTokenHeader("Authorization");
        properties.setTokenPrefix("Bearer");
        properties.setAllowRemote(allowRemote);
        return properties;
    }

    /**
     * 创建真实 token 编解码器。
     *
     * @return 编解码器
     */
    private static McpTokenCodec codec() {
        return new McpTokenCodec(SECRET, Duration.ofHours(1), Duration.ofSeconds(60));
    }

    /**
     * 创建指定远端地址的请求。
     *
     * @param remoteAddr 远端地址
     * @return 请求
     */
    private static MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
