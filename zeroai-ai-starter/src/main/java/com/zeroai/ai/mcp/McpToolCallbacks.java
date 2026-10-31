package com.zeroai.ai.mcp;

import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * MCP v1 工具白名单与 schema 工厂。
 * <p>
 * 只允许在这里新增只读工具。写操作、库存扣减、支付和退款不得进入该列表。
 */
final class McpToolCallbacks {

    private static final String SEARCH_PRODUCTS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "keyword": {"type": "string", "minLength": 1, "description": "商品关键词，例如手机、连衣裙"},
                "limit": {"type": "integer", "minimum": 1, "maximum": 10, "description": "返回条数，缺省5，最大10"}
              },
              "required": ["keyword"],
              "additionalProperties": false
            }
            """;

    private static final String PRODUCT_DETAIL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "goodsId": {"type": "integer", "minimum": 1, "description": "商品ID，来自 search_products"}
              },
              "required": ["goodsId"],
              "additionalProperties": false
            }
            """;

    private static final String SEARCH_KNOWLEDGE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {"type": "string", "minLength": 1, "description": "用户关于商城规则的原意问题"},
                "limit": {"type": "integer", "minimum": 1, "maximum": 10, "description": "返回片段数，缺省3"}
              },
              "required": ["query"],
              "additionalProperties": false
            }
            """;

    private static final String LIST_MY_ORDERS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "limit": {"type": "integer", "minimum": 1, "maximum": 5, "description": "返回条数，缺省3，最大5"}
              },
              "additionalProperties": false
            }
            """;

    private McpToolCallbacks() {
    }

    /**
     * 创建固定的四个只读工具回调。
     *
     * @param tools      MCP 工具适配层
     * @param jsonMapper 参数解析器
     * @return 不可变工具列表
     */
    static List<ToolCallback> create(McpMallTools tools, JsonMapper jsonMapper) {
        return List.of(
                new McpToolCallback(jsonMapper, McpToolNames.SEARCH_PRODUCTS,
                        "按关键词检索在售商品，返回商品ID、名称、价格和销量。只读操作。",
                        SEARCH_PRODUCTS_SCHEMA,
                        (args, context) -> {
                            McpToolArguments.requireOnlyFields(args, "keyword", "limit");
                            return tools.searchProducts(
                                    McpToolArguments.requiredText(args, "keyword"),
                                    McpToolArguments.optionalInt(args, "limit", 1, 10), context);
                        }),
                new McpToolCallback(jsonMapper, McpToolNames.GET_PRODUCT_DETAIL,
                        "按商品ID查询名称、价格、简介、可选规格和库存状态。只读操作。",
                        PRODUCT_DETAIL_SCHEMA,
                        (args, context) -> {
                            McpToolArguments.requireOnlyFields(args, "goodsId");
                            return tools.getProductDetail(
                                    McpToolArguments.requiredLong(args, "goodsId", 1), context);
                        }),
                new McpToolCallback(jsonMapper, McpToolNames.SEARCH_KNOWLEDGE,
                        "检索退款售后、配送运费、下单支付、优惠券等公开政策。只读操作，不替代商品或订单查询。",
                        SEARCH_KNOWLEDGE_SCHEMA,
                        (args, context) -> {
                            McpToolArguments.requireOnlyFields(args, "query", "limit");
                            return tools.searchKnowledge(
                                    McpToolArguments.requiredText(args, "query"),
                                    McpToolArguments.optionalInt(args, "limit", 1, 10), context);
                        }),
                new McpToolCallback(jsonMapper, McpToolNames.LIST_MY_ORDERS,
                        "查询当前 token 所属用户自己的订单列表，返回订单号、金额、状态与商品名。只读操作。",
                        LIST_MY_ORDERS_SCHEMA,
                        (args, context) -> {
                            McpToolArguments.requireOnlyFields(args, "limit");
                            return tools.listMyOrders(
                                    McpToolArguments.optionalInt(args, "limit", 1, 5), context);
                        })
        );
    }
}
