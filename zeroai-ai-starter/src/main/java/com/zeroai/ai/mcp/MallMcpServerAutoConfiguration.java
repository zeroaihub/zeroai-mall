package com.zeroai.ai.mcp;

import com.zeroai.ai.knowledge.KnowledgeService;
import com.zeroai.ai.mall.port.MallSearchPort;
import com.zeroai.domain.api.goods.service.IGoodsDetailService;
import com.zeroai.domain.api.trade.service.IMobileOrderService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * 商城 MCP Server 自动装配。
 * <p>
 * 只有 MCP 与 Spring AI MCP Server 双重开关同时打开时才注册 Bean。MCP 只暴露
 * {@link McpMallTools} 中显式声明的只读工具，不扫描或暴露 AgentScope 的全部工具。
 */
@AutoConfiguration(before = McpServerStreamableHttpWebMvcAutoConfiguration.class)
@ConditionalOnClass({WebMvcStreamableServerTransportProvider.class,
        io.modelcontextprotocol.spec.McpSchema.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnExpression("${zeroai.ai.mcp.enabled:false} && ${spring.ai.mcp.server.enabled:false}")
@EnableConfigurationProperties({
        McpProperties.class,
        McpServerStreamableHttpProperties.class
})
public class MallMcpServerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MallMcpServerAutoConfiguration.class);

    /**
     * 创建 MCP token 校验器，并在配置缺失时阻止应用带病启动。
     *
     * @param properties MCP 配置
     * @return token 编解码器
     */
    @Bean
    @ConditionalOnMissingBean
    public McpTokenCodec mcpTokenCodec(McpProperties properties) {
        return new McpTokenCodec(properties.getTokenSecret(),
                properties.getMaxTokenTtl(), properties.getClockSkew());
    }

    /**
     * 注册 MCP HTTP 认证过滤器。
     *
     * @param tokenCodec token 编解码器
     * @param properties MCP 配置
     * @param serverProperties Spring AI MCP Streamable HTTP 配置
     * @return 过滤器注册器
     */
    @Bean
    public FilterRegistrationBean<McpAuthenticationFilter> mcpAuthenticationFilter(
            McpTokenCodec tokenCodec,
            McpProperties properties,
            McpServerStreamableHttpProperties serverProperties) {
        FilterRegistrationBean<McpAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new McpAuthenticationFilter(tokenCodec, properties));
        registration.addUrlPatterns(serverProperties.getMcpEndpoint());
        registration.setName("mcpAuthenticationFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    /**
     * 创建 MCP 身份解析器。
     *
     * @return 身份解析器
     */
    @Bean
    @ConditionalOnMissingBean
    public McpPrincipalResolver mcpPrincipalResolver() {
        return new McpPrincipalResolver();
    }

    /**
     * 创建只读 MCP 工具适配层。
     *
     * @param searchProvider   商品检索端口提供者
     * @param goodsDetailService 商品详情服务
     * @param knowledgeProvider 知识检索服务提供者
     * @param mobileOrderService 订单服务
     * @param principalResolver MCP 身份解析器
     * @return MCP 工具适配层
     */
    @Bean
    @ConditionalOnMissingBean
    public McpMallTools mcpMallTools(ObjectProvider<MallSearchPort> searchProvider,
                                     IGoodsDetailService goodsDetailService,
                                     ObjectProvider<KnowledgeService> knowledgeProvider,
                                     IMobileOrderService mobileOrderService,
                                     McpPrincipalResolver principalResolver) {
        return new McpMallTools(searchProvider.getIfAvailable(),
                goodsDetailService,
                knowledgeProvider.getIfAvailable(),
                mobileOrderService,
                principalResolver);
    }

    /**
     * 只注册显式挑选的只读工具，禁止自动扫描并暴露其他 ToolCallback。
     *
     * @param tools MCP 工具适配层
     * @return 工具回调提供者
     */
    @Bean
    @ConditionalOnMissingBean(name = "mallMcpToolCallbackProvider")
    public ToolCallbackProvider mallMcpToolCallbackProvider(
            McpMallTools tools,
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper) {
        List<ToolCallback> callbacks = McpToolCallbacks.create(tools, jsonMapper);
        return ToolCallbackProvider.from(callbacks);
    }

    /**
     * 覆盖默认 transport provider，在每次 HTTP 消息中提取认证身份并写入 MCP context。
     *
     * @param jsonMapper Spring AI MCP 专用 Jackson 3 mapper
     * @param serverProperties Streamable HTTP 配置
     * @param properties MCP 安全配置
     * @return Streamable HTTP transport provider
     */
    @Bean
    @ConditionalOnMissingBean
    public WebMvcStreamableServerTransportProvider mcpTransportProvider(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
            McpServerStreamableHttpProperties serverProperties,
            McpProperties properties) {
        WebMvcStreamableServerTransportProvider.Builder builder =
                WebMvcStreamableServerTransportProvider.builder()
                        .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                        .mcpEndpoint(serverProperties.getMcpEndpoint())
                        .disallowDelete(serverProperties.isDisallowDelete())
                        .contextExtractor(request -> {
                            Object value = request.servletRequest()
                                    .getAttribute(McpAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
                            return value instanceof McpPrincipal principal
                                    ? McpTransportContext.create(Map.of(McpPrincipal.CONTEXT_KEY, principal))
                                    : McpTransportContext.EMPTY;
                        });
        if (serverProperties.getKeepAliveInterval() != null) {
            builder.keepAliveInterval(serverProperties.getKeepAliveInterval());
        }
        log.info("MCP Server 已启用 endpoint={} allowRemote={} tools={}",
                serverProperties.getMcpEndpoint(), properties.isAllowRemote(),
                "search_products,get_product_detail,search_knowledge,list_my_orders");
        return builder.build();
    }
}
