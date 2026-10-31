package com.zeroai.ai.mcp;

import com.zeroai.ai.knowledge.KnowledgeService;
import com.zeroai.ai.mall.port.MallSearchPort;
import com.zeroai.ai.mall.tool.GoodsDetailTool;
import com.zeroai.ai.mall.tool.MyOrdersTool;
import com.zeroai.ai.mall.tool.SearchKnowledgeTool;
import com.zeroai.ai.mall.tool.SearchProductsTool;
import com.zeroai.domain.api.goods.service.IGoodsDetailService;
import com.zeroai.domain.api.trade.service.IMobileOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

import java.util.function.Function;

/**
 * MCP 只读工具适配层。
 * <p>
 * 本类只负责 Spring AI/MCP 协议适配、身份解析和审计，实际查询继续复用
 * P3/P7 的只读工具，避免复制商品、订单和知识检索业务逻辑。
 * 工具参数中不存在 userId；用户级查询只能使用验签后的 transport context 身份。
 */
public class McpMallTools {

    private static final Logger log = LoggerFactory.getLogger(McpMallTools.class);
    private static final String AUTH_REQUIRED_JSON =
            "{\"error\":\"AUTH_REQUIRED\",\"hint\":\"MCP 调用缺少有效用户身份，请重新签发访问凭据\"}";
    private static final String TOOL_UNAVAILABLE_JSON =
            "{\"error\":\"MCP_TOOL_UNAVAILABLE\",\"hint\":\"服务暂时不可用，请稍后再试\"}";

    private final SearchProductsTool searchProductsTool;
    private final GoodsDetailTool goodsDetailTool;
    private final SearchKnowledgeTool searchKnowledgeTool;
    private final IMobileOrderService mobileOrderService;
    private final McpPrincipalResolver principalResolver;

    /**
     * @param searchPort          商品检索端口，可为 null
     * @param goodsDetailService  商品详情服务
     * @param knowledgeService    知识检索服务，可为 null
     * @param mobileOrderService  当前用户订单服务
     * @param principalResolver   MCP 身份解析器
     */
    public McpMallTools(MallSearchPort searchPort,
                        IGoodsDetailService goodsDetailService,
                        KnowledgeService knowledgeService,
                        IMobileOrderService mobileOrderService,
                        McpPrincipalResolver principalResolver) {
        this.searchProductsTool = new SearchProductsTool(searchPort);
        this.goodsDetailTool = new GoodsDetailTool(goodsDetailService);
        this.searchKnowledgeTool = new SearchKnowledgeTool(knowledgeService);
        this.mobileOrderService = mobileOrderService;
        this.principalResolver = principalResolver;
    }

    /**
     * 检索在售商品。
     *
     * @param keyword     商品关键词
     * @param limit       返回条数，缺省 5，最大 10
     * @param toolContext 服务端注入的 MCP 上下文，不进入工具 schema
     * @return JSON 字符串
     */
    public String searchProducts(String keyword, Integer limit, ToolContext toolContext) {
        return execute(McpToolNames.SEARCH_PRODUCTS, toolContext,
                principal -> searchProductsTool.searchProducts(keyword, limit));
    }

    /**
     * 查询单个商品详情。
     *
     * @param goodsId     商品 ID
     * @param toolContext 服务端注入的 MCP 上下文，不进入工具 schema
     * @return JSON 字符串
     */
    public String getProductDetail(Long goodsId, ToolContext toolContext) {
        return execute(McpToolNames.GET_PRODUCT_DETAIL, toolContext,
                principal -> goodsDetailTool.getProductDetail(goodsId));
    }

    /**
     * 检索公开商城政策与规则。
     *
     * @param query       自然语言问题
     * @param limit       返回片段数，缺省 3
     * @param toolContext 服务端注入的 MCP 上下文，不进入工具 schema
     * @return JSON 字符串
     */
    public String searchKnowledge(String query, Integer limit, ToolContext toolContext) {
        return execute(McpToolNames.SEARCH_KNOWLEDGE, toolContext,
                principal -> searchKnowledgeTool.searchKnowledge(query, limit));
    }

    /**
     * 查询当前 token 所属用户自己的订单。
     *
     * @param limit       返回条数，缺省 3，最大 5
     * @param toolContext 服务端注入的 MCP 上下文，不进入工具 schema
     * @return JSON 字符串；用户身份只来自 transport context
     */
    public String listMyOrders(Integer limit, ToolContext toolContext) {
        return execute(McpToolNames.LIST_MY_ORDERS, toolContext,
                principal -> new MyOrdersTool(mobileOrderService, principal.userId()).listMyOrders(limit));
    }

    /**
     * 统一执行身份校验、异常收敛与安全审计。
     *
     * @param toolName    工具名
     * @param toolContext MCP 工具上下文
     * @param invocation  实际只读调用
     * @return 工具 JSON 结果
     */
    private String execute(String toolName, ToolContext toolContext,
                           Function<McpPrincipal, String> invocation) {
        long start = System.nanoTime();
        McpPrincipal principal;
        try {
            principal = principalResolver.require(toolContext);
        } catch (McpAuthenticationException e) {
            log.warn("ai.mcp.tool.rejected tool={} reason={}", toolName, e.getReason());
            return AUTH_REQUIRED_JSON;
        }

        String status = "OK";
        try {
            return invocation.apply(principal);
        } catch (RuntimeException e) {
            status = "ERROR";
            log.warn("ai.mcp.tool.failed tool={} client={} user={} error={}",
                    toolName, principal.clientId(), principal.userId(), e.getClass().getSimpleName());
            return TOOL_UNAVAILABLE_JSON;
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            // 不记录工具参数、返回值、token 或订单内容。
            log.info("ai.mcp.tool.call tool={} client={} user={} ms={} status={}",
                    toolName, principal.clientId(), principal.userId(), elapsedMs, status);
        }
    }
}
