package com.zeroai.ai.mcp;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 显式定义的 MCP v1 工具回调。
 * <p>
 * 不依赖 Spring AI 的反射 schema 生成器，避免与 AgentScope 所需 victools 4.38
 * 发生二进制冲突。schema、工具名和错误格式都在编译期固定，便于兼容性测试。
 */
final class McpToolCallback implements ToolCallback {

    private static final String INVALID_ARGUMENTS_JSON =
            "{\"error\":\"INVALID_ARGUMENTS\",\"hint\":\"工具参数格式不正确，请按 schema 重试\"}";

    private final JsonMapper jsonMapper;
    private final ToolDefinition definition;
    private final McpToolInvoker invoker;

    /**
     * @param jsonMapper  参数解析器
     * @param name        工具名
     * @param description 工具说明
     * @param inputSchema v1 JSON schema
     * @param invoker     实际只读调用
     */
    McpToolCallback(JsonMapper jsonMapper, String name, String description,
                    String inputSchema, McpToolInvoker invoker) {
        this.jsonMapper = jsonMapper;
        this.definition = DefaultToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
        this.invoker = invoker;
    }

    /**
     * 兼容无 ToolContext 的工具调用。
     *
     * @param inputJson 工具参数 JSON
     * @return 工具结果
     */
    @Override
    public String call(String inputJson) {
        return call(inputJson, null);
    }

    /**
     * 解析 JSON 参数并执行工具。
     *
     * @param inputJson   工具参数 JSON
     * @param toolContext MCP 工具上下文
     * @return 工具结果
     */
    @Override
    public String call(String inputJson, ToolContext toolContext) {
        try {
            JsonNode arguments = inputJson == null || inputJson.isBlank()
                    ? jsonMapper.readTree("{}")
                    : jsonMapper.readTree(inputJson);
            if (arguments == null || !arguments.isObject()) {
                return INVALID_ARGUMENTS_JSON;
            }
            return invoker.invoke(arguments, toolContext);
        } catch (IllegalArgumentException e) {
            return INVALID_ARGUMENTS_JSON;
        } catch (RuntimeException e) {
            // 解析异常不向上返回底层库信息，统一交给工具层给出稳定错误。
            return INVALID_ARGUMENTS_JSON;
        } catch (Exception e) {
            return INVALID_ARGUMENTS_JSON;
        }
    }

    /**
     * @return 固定工具定义
     */
    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    /**
     * 工具调用函数。
     */
    @FunctionalInterface
    interface McpToolInvoker {

        /**
         * @param arguments   已解析参数
         * @param toolContext MCP 工具上下文
         * @return 工具结果
         */
        String invoke(JsonNode arguments, ToolContext toolContext);
    }
}
