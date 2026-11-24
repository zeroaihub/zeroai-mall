package com.zeroai.ai.engine.langchain4j.tool;

/**
 * 工具返回值的最小 JSON 拼装器（LangChain4j 版）。
 * <p>
 * 与 {@code com.zeroai.ai.mall.tool.ToolJson}（AgentScope 版）内容一致：
 * 刻意不用 Jackson——本模块同时存在 Jackson 2（LangChain4j/AgentScope）与 Jackson 3（Boot 4），
 * 工具结果必须完全可控。只做字符串转义，不引入序列化框架。
 * <p>
 * <b>隔离说明</b>：本包不得 import {@code mall.tool} 的任何类型（含此工具类的原版），
 * 因此这里保留一份平行副本。两版必须同步修改，见 ADR-003。
 */
final class JsonText {

    private JsonText() {
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
