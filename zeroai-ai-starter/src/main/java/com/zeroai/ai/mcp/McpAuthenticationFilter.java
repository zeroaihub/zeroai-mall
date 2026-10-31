package com.zeroai.ai.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * MCP HTTP 入口认证过滤器。
 * <p>
 * 负责默认本地访问限制、Bearer token 提取和验签。验签成功后才把
 * {@link McpPrincipal} 放入 request attribute，供 transport context extractor 读取。
 * 过滤器不打印 token，也不会把验签失败细节返回给客户端。
 */
public class McpAuthenticationFilter extends OncePerRequestFilter {

    /** 认证成功后写入 request 的身份属性名。 */
    public static final String PRINCIPAL_ATTRIBUTE = McpPrincipal.class.getName();

    private static final Logger log = LoggerFactory.getLogger(McpAuthenticationFilter.class);
    private static final String JSON_UNAUTHORIZED =
            "{\"error\":\"MCP_UNAUTHORIZED\",\"message\":\"缺少或无效的 MCP 访问凭据\"}";
    private static final String JSON_FORBIDDEN =
            "{\"error\":\"MCP_REMOTE_FORBIDDEN\",\"message\":\"MCP 默认只允许本机访问\"}";

    private final McpTokenCodec tokenCodec;
    private final McpProperties properties;

    /**
     * @param tokenCodec token 编解码器
     * @param properties MCP 配置
     */
    public McpAuthenticationFilter(McpTokenCodec tokenCodec, McpProperties properties) {
        this.tokenCodec = tokenCodec;
        this.properties = properties;
    }

    /**
     * 执行 MCP 入口认证，并在请求结束后保留 request 作用域内的身份供工具读取。
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @param chain    后续过滤器链
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.isAllowRemote() && !isLoopback(request.getRemoteAddr())) {
            log.warn("mcp.auth.rejected remote={} reason=REMOTE_FORBIDDEN", safeRemote(request.getRemoteAddr()));
            reject(response, HttpServletResponse.SC_FORBIDDEN, JSON_FORBIDDEN);
            return;
        }

        try {
            McpPrincipal principal = tokenCodec.verify(extractToken(request));
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            chain.doFilter(request, response);
        } catch (McpAuthenticationException e) {
            log.warn("mcp.auth.rejected remote={} reason={}",
                    safeRemote(request.getRemoteAddr()), e.getReason());
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, JSON_UNAUTHORIZED);
        }
    }

    /**
     * 从配置的请求头提取 token，兼容 Bearer 前缀和移动端归一化后的裸 token。
     *
     * @param request HTTP 请求
     * @return 原始 token；缺失时返回 null
     */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(properties.getTokenHeader());
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        String prefix = properties.getTokenPrefix();
        if (prefix == null || prefix.isBlank()) {
            return value;
        }
        String expected = prefix.trim() + " ";
        if (value.regionMatches(true, 0, expected, 0, expected.length())
                && value.length() > expected.length()) {
            return value.substring(expected.length()).trim();
        }
        // 移动端全局过滤器会把 Bearer 前缀归一化为裸 token；MCP 验签仍以
        // token 的 HMAC 为准，因此这里兼容无空格的裸 token，但拒绝其他认证方案。
        if (!value.contains(" ")) {
            return value;
        }
        return null;
    }

    /**
     * 判断远端地址是否为回环地址。代理头不可信，因此只检查 socket 远端地址。
     *
     * @param remoteAddr 远端地址
     * @return 本机访问返回 true
     */
    private boolean isLoopback(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 返回可安全写入日志的远端地址。
     *
     * @param remoteAddr 原始远端地址
     * @return 截断后的地址
     */
    private static String safeRemote(String remoteAddr) {
        if (remoteAddr == null) {
            return "unknown";
        }
        return remoteAddr.length() <= 64 ? remoteAddr : remoteAddr.substring(0, 64);
    }

    /**
     * 输出稳定 JSON 错误，不返回异常栈或验签细节。
     *
     * @param response HTTP 响应
     * @param status   HTTP 状态码
     * @param body     JSON 响应体
     */
    private static void reject(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"zeroai-mcp\"");
        }
        response.getWriter().write(body);
    }

}
