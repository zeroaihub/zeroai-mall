package com.zeroai.ai.agent;

import com.zeroai.ai.config.AiAgentProperties;
import com.zeroai.ai.harness.HarnessProperties;
import com.zeroai.ai.history.AiChatHistory;
import com.zeroai.ai.mall.port.MallSearchPort;
import com.zeroai.ai.mall.proposal.ActionProposalProperties;
import com.zeroai.ai.mall.proposal.ActionProposalService;
import com.zeroai.ai.memory.UserPreferenceMemory;
import com.zeroai.domain.api.cart.service.ICartService;
import com.zeroai.domain.api.goods.service.IGoodsDetailService;
import com.zeroai.domain.api.goods.service.IGoodsProductService;
import com.zeroai.domain.api.trade.service.IAddressService;
import com.zeroai.domain.api.trade.service.IMobileOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * AgentScope 通道自动装配。
 * <p>
 * 生效条件：{@code zeroai.ai.enabled=true} <b>且</b> {@code zeroai.ai.engine=agentscope}。
 * 任一不满足时不注册任何 Bean，应用行为与接入前完全一致。
 * <p>
 * 为什么用 {@link ConditionalOnExpression} 而不是两个 {@code @ConditionalOnProperty}：
 * 后者不支持在同一注解上表达「与」关系，且 {@code matchIfMissing} 在双条件下语义容易写错。
 * 这里显式求值，条件一目了然。
 * <p>
 * 与 {@link com.zeroai.ai.config.AiAgentAutoConfiguration} 的分工：
 * 那套负责单轮无工具问答（Spring AI ChatClient），本套负责带工具的 Agent 通道。
 * 两者共用 {@link AiAgentProperties} 中的同一组 baseUrl / apiKey / model，
 * 不存在第二套密钥约定。
 */
@AutoConfiguration
@ConditionalOnExpression("${zeroai.ai.enabled:false} && '${zeroai.ai.engine:spring-ai}' == 'agentscope'")
@EnableConfigurationProperties({AiAgentProperties.class, HarnessProperties.class, ActionProposalProperties.class})
public class AgentScopeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeAutoConfiguration.class);

    /**
     * 注册 Agent 工厂。
     * <p>
     * 检索端口用 {@link ObjectProvider} 取：搜索实现位于入口模块，
     * 本模块不反向依赖入口层。入口未提供时工厂仍能创建，只是搜索工具返回显式降级。
     *
     * @param properties          AI 配置
     * @param harness             Harness 能力开关
     * @param searchPortProvider  检索端口提供者，可为空
     * @param goodsDetailService  商品详情服务
     * @param goodsProductService 商品货品服务（只读用途）
     * @param mobileOrderService  订单服务（只读用途）
     * @return Agent 工厂
     */
    @Bean
    @ConditionalOnMissingBean
    public MallAgentFactory mallAgentFactory(AiAgentProperties properties,
                                             HarnessProperties harness,
                                             ObjectProvider<MallSearchPort> searchPortProvider,
                                             IGoodsDetailService goodsDetailService,
                                             IGoodsProductService goodsProductService,
                                             IMobileOrderService mobileOrderService,
                                             IAddressService addressService,
                                             ICartService cartService,
                                             ObjectProvider<ActionProposalService> actionProposalProvider,
                                             ActionProposalProperties actionProperties) {
        // 能力开关必须可见：默认全关，若有人误开需要能从启动日志一眼看出
        log.info("Agent 通道配置 engine=agentscope model={} skillsEnabled={} allowedSkills={} "
                        + "memoryEnabled={} observabilityEnabled={} planModeEnabled={} actionEnabled={}",
                properties.getModel(), harness.isSkillsEnabled(), harness.getAllowedSkills(),
                harness.isMemoryEnabled(), harness.isObservabilityEnabled(), harness.isPlanModeEnabled(),
                actionProperties.isEnabled());
        return new MallAgentFactory(properties,
                harness,
                searchPortProvider.getIfAvailable(),
                goodsDetailService,
                goodsProductService,
                mobileOrderService,
                addressService,
                cartService,
                actionProposalProvider.getIfAvailable(),
                actionProperties);
    }

    /**
     * 注册长期偏好记忆（按用户隔离的文件存储）。
     * <p>
     * 仅在 {@code zeroai.ai.harness.memory-enabled=true} 时创建，
     * 未启用时下游拿不到该 Bean，也就无法在无意中写入用户偏好。
     *
     * @param harness Harness 配置
     * @return 偏好记忆实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("${zeroai.ai.harness.memory-enabled:false}")
    public UserPreferenceMemory userPreferenceMemory(HarnessProperties harness) {
        return new UserPreferenceMemory(Path.of(harness.getMemoryDir()),
                harness.getMemoryMaxEntriesPerUser());
    }

    /**
     * 注册 Agent 通道请求编排器。
     * <p>
     * 该 Bean 的存在与否即代表「Agent 通道是否可用」，
     * {@code AiChatController} 通过 {@link ObjectProvider} 探测它完成引擎分派。
     *
     * @param agentFactory Agent 工厂
     * @param properties   AI 配置
     * @param chatHistory  对话历史门面，入口未提供存储时为空操作
     * @return Agent 请求编排器
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentChatHandler agentChatHandler(MallAgentFactory agentFactory,
                                             AiAgentProperties properties,
                                             AiChatHistory chatHistory) {
        return new AgentChatHandler(agentFactory, properties, chatHistory);
    }
}
