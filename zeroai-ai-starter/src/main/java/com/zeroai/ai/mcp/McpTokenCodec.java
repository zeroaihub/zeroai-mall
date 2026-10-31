package com.zeroai.ai.mcp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * MCP 短期签名 token 的签发与校验。
 * <p>
 * token 格式为 {@code v1.userId.clientId.issuedAt.expiresAt.signature}，
 * 签名覆盖前五段。该 token 只绑定用户身份，不携带订单、地址、手机号等业务数据。
 * 签发入口不暴露为 HTTP 接口，本地联调通过脚本使用同一密钥离线生成。
 */
public final class McpTokenCodec {

    private static final String VERSION = "v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MIN_SECRET_BYTES = 32;
    private static final int MAX_TOKEN_CHARS = 1024;
    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Duration maxTokenTtl;
    private final Duration clockSkew;
    private final Clock clock;

    /**
     * @param secret       HMAC 密钥，至少 32 字节
     * @param maxTokenTtl  单个 token 允许的最长有效期
     * @param clockSkew    校验允许的时钟偏差
     */
    public McpTokenCodec(String secret, Duration maxTokenTtl, Duration clockSkew) {
        this(secret, maxTokenTtl, clockSkew, Clock.systemUTC());
    }

    /**
     * 可注入时钟的构造器，供确定性测试使用。
     *
     * @param secret      HMAC 密钥
     * @param maxTokenTtl 最大有效期
     * @param clockSkew   允许时钟偏差
     * @param clock       时间源
     */
    McpTokenCodec(String secret, Duration maxTokenTtl, Duration clockSkew, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("MCP_TOKEN_SECRET 至少需要 32 字节，且不得写入配置文件");
        }
        if (maxTokenTtl == null || maxTokenTtl.isZero() || maxTokenTtl.isNegative()) {
            throw new IllegalStateException("MCP token 最大有效期必须大于 0");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.maxTokenTtl = maxTokenTtl;
        this.clockSkew = clockSkew == null ? Duration.ZERO : clockSkew;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 签发一个绑定用户与客户端的短期 token。
     * <p>
     * 该方法不暴露为 HTTP 接口，只用于离线联调与自动化测试。
     *
     * @param userId   商城用户 ID，必须大于 0
     * @param clientId 客户端标识，仅允许字母、数字、点、下划线和连字符
     * @param ttl      有效期，不得超过配置上限
     * @return 可直接放入 Authorization/自定义请求头的 token
     */
    public String issue(long userId, String clientId, Duration ttl) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId 必须大于 0");
        }
        if (clientId == null || !CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            throw new IllegalArgumentException("clientId 格式不合法");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(maxTokenTtl) > 0) {
            throw new IllegalArgumentException("token 有效期必须在允许范围内");
        }
        long issuedAt = Instant.now(clock).getEpochSecond();
        long expiresAt = issuedAt + ttl.toSeconds();
        String payload = userId + "." + clientId + "." + issuedAt + "." + expiresAt;
        String signingInput = VERSION + "." + payload;
        return signingInput + "." + BASE64_ENCODER.encodeToString(hmac(signingInput));
    }

    /**
     * 校验 token 的格式、签名、有效期与最大 TTL。
     *
     * @param token 客户端提交的原始 token
     * @return 验签后的身份快照
     * @throws McpAuthenticationException 任一步骤失败
     */
    public McpPrincipal verify(String token) {
        if (token == null || token.isBlank()) {
            throw new McpAuthenticationException("MISSING_TOKEN");
        }
        if (token.length() > MAX_TOKEN_CHARS) {
            throw new McpAuthenticationException("TOKEN_TOO_LONG");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 6 || !VERSION.equals(parts[0])) {
            throw new McpAuthenticationException("MALFORMED_TOKEN");
        }

        String payload = parts[1] + "." + parts[2] + "." + parts[3] + "." + parts[4];
        byte[] expected = hmac(VERSION + "." + payload);
        byte[] actual;
        try {
            actual = BASE64_DECODER.decode(parts[5]);
        } catch (IllegalArgumentException e) {
            throw new McpAuthenticationException("MALFORMED_SIGNATURE");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new McpAuthenticationException("INVALID_SIGNATURE");
        }

        long userId;
        long issuedAt;
        long expiresAt;
        try {
            userId = Long.parseLong(parts[1]);
            issuedAt = Long.parseLong(parts[3]);
            expiresAt = Long.parseLong(parts[4]);
        } catch (NumberFormatException e) {
            throw new McpAuthenticationException("MALFORMED_TOKEN");
        }
        String clientId = parts[2];
        if (userId <= 0 || issuedAt <= 0 || expiresAt <= issuedAt
                || !CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            throw new McpAuthenticationException("MALFORMED_TOKEN");
        }

        long now = Instant.now(clock).getEpochSecond();
        long skewSeconds = Math.max(0, clockSkew.toSeconds());
        if (issuedAt > now + skewSeconds) {
            throw new McpAuthenticationException("TOKEN_NOT_ACTIVE");
        }
        if (expiresAt < now - skewSeconds) {
            throw new McpAuthenticationException("TOKEN_EXPIRED");
        }
        if (Duration.ofSeconds(expiresAt - issuedAt).compareTo(maxTokenTtl) > 0) {
            throw new McpAuthenticationException("TOKEN_TTL_TOO_LONG");
        }
        return new McpPrincipal(userId, clientId, issuedAt, expiresAt);
    }

    /**
     * 计算 HMAC-SHA256。
     *
     * @param input 待签名字符串
     * @return 签名字节
     */
    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("当前 JVM 不支持 " + HMAC_ALGORITHM, e);
        }
    }
}
