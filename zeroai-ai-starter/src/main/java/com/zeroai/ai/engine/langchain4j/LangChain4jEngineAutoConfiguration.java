package com.zeroai.ai.engine.langchain4j;

import com.zeroai.ai.config.AiAgentProperties;
import com.zeroai.ai.engine.langchain4j.agent.Lc4jAgentHandler;
import com.zeroai.ai.history.AiChatHistory;
import com.zeroai.ai.knowledge.KnowledgeService;
import com.zeroai.ai.mall.port.MallSearchPort;
import com.zeroai.ai.mall.proposal.ActionProposalProperties;
import com.zeroai.ai.mall.proposal.ActionProposalService;
import com.zeroai.ai.prompt.PromptLoader;
import com.zeroai.domain.api.cart.service.ICartService;
import com.zeroai.domain.api.goods.service.IGoodsDetailService;
import com.zeroai.domain.api.goods.service.IGoodsProductService;
import com.zeroai.domain.api.goods.service.IGoodsService;
import com.zeroai.domain.api.trade.service.IAddressService;
import com.zeroai.domain.api.trade.service.IMobileOrderService;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;

/**
 * LangChain4j 通道装配。
 * <p>
 * 只在 {@code zeroai.ai.engine=langchain4j} 时生效，其余取值下本类一行代码都不执行，
 * LangChain4j 的模型对象也不会被创建。关掉开关就等于彻底不接入。
 * <p>
 * 复用 {@link AiAgentProperties} 的 baseUrl / apiKey / model / temperature / maxTokens /
 * systemPrompt：两套引擎必须能对着同一份配置直接对拍，否则「切换引擎」就变成了「切换模型」，
 * 体验差异无法归因到框架本身。
 * <p>
 * 本类注册两样东西：单轮流式引擎（降级兜底）与带工具的 Agent 编排器（主链路，
 * 对标 AgentScope 的 AgentChatHandler + MallAgentFactory）。业务服务 Bean
 * （商品/购物车/订单/地址/提议）与 AgentScope 通道共享同一批宿主 Bean，见 ADR-003。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "zeroai.ai", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "zeroai.ai", name = "engine", havingValue = "langchain4j")
@EnableConfigurationProperties(AiAgentProperties.class)
public class LangChain4jEngineAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jEngineAutoConfiguration.class);

    /**
     * LangChain4j 单轮引擎（降级兜底：Agent 编排异常时保持对话可用）。
     *
     * @param properties AI 配置
     * @return 引擎实现
     */
    @Bean
    @ConditionalOnMissingBean
    public LangChain4jChatStreamEngine langChain4jChatStreamEngine(AiAgentProperties properties) {
        return new LangChain4jChatStreamEngine(buildStreamingModel(properties),
                PromptLoader.load(properties.getSystemPrompt()));
    }

    /**
     * LangChain4j 带工具的 Agent 编排器。
     * <p>
     * 业务服务与 AgentScope 通道共享同一批宿主 Bean；工具实现是 langchain4j.tool 包的
     * 平行版本（不 import mall.tool），见 ADR-003。能力开关日志与 AgentScope 装配对齐，
     * 便于切换引擎时直接对比两边的装配结果。
     *
     * @param properties          AI 配置
     * @param model               流式模型
     * @param searchPortProvider  检索端口提供者，可为空
     * @param goodsDetailService  商品详情服务
     * @param goodsService        商品服务（按 ID 回显商品卡）
     * @param goodsProductService 商品货品服务（只读用途）
     * @param mobileOrderService  订单服务（只读用途）
     * @param addressService      地址服务（只读用途）
     * @param cartService         购物车服务
     * @param actionProposalProvider 提议服务提供者，写操作提议关闭时为空
     * @param actionProperties    提议配置
     * @param knowledgeProvider   知识检索门面提供者，知识库关闭时为空
     * @param chatHistoryProvider 对话历史门面提供者
     * @return Agent 编排器
     */
    @Bean
    @ConditionalOnMissingBean
    public Lc4jAgentHandler lc4jAgentHandler(AiAgentProperties properties,
                                             ObjectProvider<MallSearchPort> searchPortProvider,
                                             ObjectProvider<IGoodsDetailService> goodsDetailProvider,
                                             ObjectProvider<IGoodsService> goodsServiceProvider,
                                             ObjectProvider<IGoodsProductService> goodsProductProvider,
                                             ObjectProvider<IMobileOrderService> mobileOrderProvider,
                                             ObjectProvider<IAddressService> addressProvider,
                                             ObjectProvider<ICartService> cartProvider,
                                             ObjectProvider<ActionProposalService> actionProposalProvider,
                                             ObjectProvider<ActionProposalProperties> actionPropertiesProvider,
                                             ObjectProvider<KnowledgeService> knowledgeProvider,
                                             ObjectProvider<AiChatHistory> chatHistoryProvider) {
        KnowledgeService knowledgeService = knowledgeProvider.getIfAvailable();
        // 提议配置只在提议模块装配时注册；缺失（如最小上下文测试）时用默认值，即提议关闭
        ActionProposalProperties actionProperties = actionPropertiesProvider.getIfAvailable();
        if (actionProperties == null) {
            actionProperties = new ActionProposalProperties();
        }
        MallSearchPort searchPort = searchPortProvider.getIfAvailable();
        log.info("LangChain4j Agent 通道配置 engine=langchain4j model={} actionEnabled={} knowledgeEnabled={}"
                        + " searchPortWired={} cartServiceWired={}",
                properties.getModel(), actionProperties.isEnabled(), knowledgeService != null,
                searchPort != null, cartProvider.getIfAvailable() != null);
        return new Lc4jAgentHandler(properties,
                buildStreamingModel(properties),
                PromptLoader.load(properties.getSystemPrompt()),
                searchPort,
                goodsDetailProvider.getIfAvailable(),
                goodsServiceProvider.getIfAvailable(),
                goodsProductProvider.getIfAvailable(),
                mobileOrderProvider.getIfAvailable(),
                addressProvider.getIfAvailable(),
                cartProvider.getIfAvailable(),
                actionProposalProvider.getIfAvailable(),
                actionProperties,
                knowledgeService,
                chatHistoryProvider.getIfAvailable());
    }

    /**
     * 构建 OpenAI 兼容的流式模型。
     * <p>
     * 显式关掉 SDK 自带的请求/响应日志：它会把完整 Prompt 与用户原话打到 INFO，
     * 而导购场景的用户输入可能带手机号、地址、订单号，不能进日志。
     *
     * @param properties AI 配置
     * @return 流式模型
     */
    private StreamingChatModel buildStreamingModel(AiAgentProperties properties) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException(
                    "zeroai.ai.api-key 未配置：请通过环境变量 LLM_API_KEY 注入，禁止写入配置文件");
        }
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(resolveBaseUrl(properties.getBaseUrl()))
                .apiKey(properties.getApiKey())
                .modelName(properties.getModel())
                .temperature(properties.getTemperature())
                .maxTokens(properties.getMaxTokens())
                .logRequests(false)
                .logResponses(false);
        // 超时与 SSE 连接超时对齐：模型还在生成时不能被 HTTP 读超时提前掐断。
        // 配 0 表示不额外限制，交给 SDK 默认值。
        Long timeoutMs = properties.getSseTimeoutMs();
        if (timeoutMs != null && timeoutMs > 0) {
            builder.timeout(Duration.ofMillis(timeoutMs));
        }
        return builder.build();
    }

    /**
     * 把共用的 base-url 规范化成 LangChain4j 期望的形式。
     * <p>
     * 两套 SDK 的拼法不同：Spring AI（openai-java）会把不带路径的 base-url 规范成
     * {@code <base>/v1/chat/completions}，LangChain4j 只拼 {@code <base>/chat/completions}。
     * 本项目配置约定「base-url 不带版本前缀」，若原样交给 LangChain4j，
     * 同一份配置会在两边命中不同端点——这正好破坏「切引擎只换框架、不换环境」的前提。
     * 因此这里按 Spring AI 的口径补 {@code /v1}；已带路径的（{@code /v1}、自定义前缀、网关路径）
     * 原样保留，避免二次改写。
     *
     * @param configured 配置值，非空
     * @return 规范化后的 base-url，结尾无斜杠
     */
    static String resolveBaseUrl(String configured) {
        if (!StringUtils.hasText(configured)) {
            throw new IllegalStateException("zeroai.ai.base-url 未配置");
        }
        String trimmed = configured.trim().replaceAll("/+$", "");
        String path = URI.create(trimmed).getPath();
        boolean versioned = path != null && !path.isEmpty();
        return versioned ? trimmed : trimmed + "/v1";
    }
}
