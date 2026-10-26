package com.zeroai.ai.mall.tool;

/**
 * 工具返回值的最小 JSON 拼装器。
 * <p>
 * 刻意不使用 Jackson：本模块同时存在 Jackson 2.21.7（AgentScope）与 Jackson 3.1.7（Boot 4），
 * 工具结果又必须完全可控。这里只做字符串转义，不引入任何序列化框架，
 * 避免两条 Jackson 版本线在本模块内产生歧义。
 */
final class ToolJson {

    private ToolJson() {
    }

    /**
     * 输出 JSON 字符串字面量，null 转为 JSON null。
     *
     * @param value 原始值
     * @return 带引号的 JSON 字符串；value 为 null 时返回 {@code null}
     */
    static String str(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    /**
     * 输出数值字面量，null 转为 JSON null。
     *
     * @param value 原始值
     * @return 数值字面量或 {@code null}
     */
    static String num(Number value) {
        return value == null ? "null" : value.toString();
    }

    /**
     * 按 JSON 规范转义控制字符与引号。
     *
     * @param raw 原始字符串
     * @return 转义后的内容，不含首尾引号
     */
    static String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
