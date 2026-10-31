package com.zeroai.ai.mcp;

/**
 * MCP v1 稳定工具名。
 * <p>
 * v1 内只允许新增兼容字段，不允许修改现有工具名或参数语义。
 * 需要破坏性变更时必须启用新的工具名并保留旧名过渡期。
 */
public final class McpToolNames {

    public static final String SEARCH_PRODUCTS = "search_products";
    public static final String GET_PRODUCT_DETAIL = "get_product_detail";
    public static final String SEARCH_KNOWLEDGE = "search_knowledge";
    public static final String LIST_MY_ORDERS = "list_my_orders";

    private McpToolNames() {
    }
}
