package com.zeroai.ai.mcp;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;

/**
 * MCP 工具入参读取器。
 * <p>
 * 入参已经由 {@link McpToolCallback} 解析为 JSON 树。这里只做类型和必填校验，
 * 业务参数范围继续由原有只读工具收敛。
 */
final class McpToolArguments {

    private McpToolArguments() {
    }

    /**
     * 读取必填字符串。
     *
     * @param args  JSON 对象
     * @param field 字段名
     * @return 非空字符串
     */
    static String requiredText(JsonNode args, String field) {
        JsonNode value = args == null ? null : args.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " 必须是非空字符串");
        }
        return value.asText();
    }

    /**
     * 读取带范围约束的可选整数。
     *
     * @param args  JSON 对象
     * @param field 字段名
     * @param min   最小值
     * @param max   最大值
     * @return 整数；字段缺失或为 null 时返回 null
     */
    static Integer optionalInt(JsonNode args, String field, int min, int max) {
        JsonNode value = args == null ? null : args.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " 必须是整数");
        }
        int result = value.asInt();
        if (result < min || result > max) {
            throw new IllegalArgumentException(field + " 超出允许范围");
        }
        return result;
    }

    /**
     * 读取带最小值约束的必填长整数。
     *
     * @param args  JSON 对象
     * @param field 字段名
     * @param min   最小值
     * @return 长整数
     */
    static Long requiredLong(JsonNode args, String field, long min) {
        JsonNode value = args == null ? null : args.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(field + " 必须是整数");
        }
        long result = value.asLong();
        if (result < min) {
            throw new IllegalArgumentException(field + " 超出允许范围");
        }
        return result;
    }

    /**
     * 拒绝 schema 未声明的字段，防止模型借额外参数扩展调用语义。
     *
     * @param args     JSON 对象
     * @param allowed  允许的字段名
     */
    static void requireOnlyFields(JsonNode args, String... allowed) {
        if (args == null) {
            throw new IllegalArgumentException("工具参数不能为空");
        }
        Set<String> allowedFields = new HashSet<>(Set.of(allowed));
        for (String field : args.propertyNames()) {
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException("工具参数包含未声明字段");
            }
        }
    }
}
