package com.zeroai.ai.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * MCP Server 的安全与认证配置。
 * <p>
 * 该配置只控制 MCP 接入边界，不改变商城业务开关。MCP 默认关闭；
 * 开启后必须配置独立的 HMAC 密钥，不能复用登录 token、模型 Key 或支付密钥。
 */
@ConfigurationProperties(prefix = "zeroai.ai.mcp")
public class McpProperties {

    /** MCP Server 总开关，默认关闭。 */
    private boolean enabled;

    /** HMAC-SHA256 密钥，必须通过环境变量注入且至少 32 字节。 */
    private String tokenSecret = "";

    /** 携带签名 token 的请求头，默认使用标准 Authorization。 */
    private String tokenHeader = "Authorization";

    /** token 前缀，默认 Bearer；置空时允许直接传裸 token。 */
    private String tokenPrefix = "Bearer";

    /** 单个 token 允许的最长有效期，防止签发长期凭据。 */
    private Duration maxTokenTtl = Duration.ofHours(1);

    /** 校验时允许的时钟偏差。 */
    private Duration clockSkew = Duration.ofSeconds(60);

    /** 是否允许非回环地址访问；生产跨服务调用应通过 TLS 反向代理并显式打开。 */
    private boolean allowRemote;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTokenSecret() {
        return tokenSecret;
    }

    public void setTokenSecret(String tokenSecret) {
        this.tokenSecret = tokenSecret;
    }

    public String getTokenHeader() {
        return tokenHeader;
    }

    public void setTokenHeader(String tokenHeader) {
        this.tokenHeader = tokenHeader;
    }

    public String getTokenPrefix() {
        return tokenPrefix;
    }

    public void setTokenPrefix(String tokenPrefix) {
        this.tokenPrefix = tokenPrefix;
    }

    public Duration getMaxTokenTtl() {
        return maxTokenTtl;
    }

    public void setMaxTokenTtl(Duration maxTokenTtl) {
        this.maxTokenTtl = maxTokenTtl;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew;
    }

    public boolean isAllowRemote() {
        return allowRemote;
    }

    public void setAllowRemote(boolean allowRemote) {
        this.allowRemote = allowRemote;
    }
}
