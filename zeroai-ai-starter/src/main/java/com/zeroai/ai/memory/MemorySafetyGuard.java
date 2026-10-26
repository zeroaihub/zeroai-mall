package com.zeroai.ai.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 长期记忆写入守卫。
 * <p>
 * 长期记忆是跨会话持久化的，一旦写入脏数据就长期存在、且会被拼进后续 Prompt，
 * 影响面比单次对话大得多。因此这里采取<b>宁可拒绝</b>的策略：
 * 命中任何一条敏感规则就整条拒绝，不做「脱敏后仍保存」——
 * 脱敏后的残片依然可能被模型拼回去，且会给人「已经安全」的错觉。
 * <p>
 * 纯函数、无状态，便于单元测试与在任意写入路径复用。
 */
public final class MemorySafetyGuard {

    /** 中国大陆手机号。前后加边界，避免把长数字串截断后误判。 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /** 18 位身份证，末位可能是 X。 */
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * 长数字串：本商城订单号 16 位，银行卡 16–19 位。
     * 两者都不该进入长期记忆，因此合并为一条规则统一拒绝。
     */
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<!\\d)\\d{12,19}(?!\\d)");

    /** 模型密钥、Bearer 令牌等凭证形态。 */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(sk-[A-Za-z0-9_-]{16,}|bearer\\s+[A-Za-z0-9._-]{16,}|ghp_[A-Za-z0-9]{20,})");

    /** 收货地址特征：省市区县 + 门牌，或「XX 路/街/小区/号楼」。 */
    private static final Pattern ADDRESS = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,8}(省|市|区|县|镇|乡))|([\\u4e00-\\u9fa5A-Za-z0-9]{2,12}(路|街|道|巷|里|小区|花园|大厦|公寓|号楼|单元|室))");

    /** 直接命中即拒绝的敏感词，覆盖地址与支付场景的高频表达。 */
    private static final List<String> SENSITIVE_KEYWORDS = List.of(
            "收货地址", "详细地址", "身份证", "银行卡", "信用卡", "验证码",
            "密码", "cvv", "支付密码", "交易流水", "订单号");

    /** 单条记忆长度上限。偏好类内容不需要很长，超长多半是在搬运业务数据。 */
    private static final int MAX_LENGTH = 200;

    private MemorySafetyGuard() {
    }

    /**
     * 审计一段待写入的长期记忆内容。
     *
     * @param content 待写入内容，可为 null
     * @return 审计结果，{@link AuditResult#allowed()} 为 false 时其 {@code reasons} 说明命中项
     */
    public static AuditResult audit(String content) {
        List<String> reasons = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return new AuditResult(false, List.of("EMPTY"));
        }
        String text = content.trim();
        if (text.length() > MAX_LENGTH) {
            reasons.add("TOO_LONG");
        }
        if (PHONE.matcher(text).find()) {
            reasons.add("PHONE");
        }
        if (ID_CARD.matcher(text).find()) {
            reasons.add("ID_CARD");
        }
        if (EMAIL.matcher(text).find()) {
            reasons.add("EMAIL");
        }
        if (LONG_DIGITS.matcher(text).find()) {
            reasons.add("ORDER_OR_BANK_CARD");
        }
        if (SECRET.matcher(text).find()) {
            reasons.add("SECRET");
        }
        if (ADDRESS.matcher(text).find()) {
            reasons.add("ADDRESS");
        }
        String lower = text.toLowerCase();
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lower.contains(keyword)) {
                reasons.add("KEYWORD:" + keyword);
            }
        }
        return new AuditResult(reasons.isEmpty(), List.copyOf(reasons));
    }

    /**
     * 审计结论。
     *
     * @param allowed 是否允许写入
     * @param reasons 拒绝原因代码列表，允许时为空
     */
    public record AuditResult(boolean allowed, List<String> reasons) {
    }
}
