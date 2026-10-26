package com.zeroai.ai.mall.proposal;

import com.zeroai.ai.mall.proposal.mapper.AiActionProposalMapper;
import com.zeroai.domain.api.cart.service.ICartService;
import com.zeroai.domain.api.goods.service.IGoodsProductService;
import com.zeroai.domain.api.promotion.service.ShopMemberCouponService;
import com.zeroai.domain.api.trade.service.IAddressService;
import com.zeroai.domain.api.trade.service.IMobileOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 写操作提议（Human-in-the-loop）自动装配。
 * <p>
 * 生效条件：{@code zeroai.ai.enabled=true} <b>且</b> {@code zeroai.ai.action.enabled=true}。
 * 任一不满足时不注册提议服务与确认接口，Agent 工具面保持纯只读。
 * <p>
 * 与 {@code AgentScopeAutoConfiguration} 解耦：确认接口是独立入口，
 * 即使对话引擎切回 {@code spring-ai}，已生成的提议仍应能被用户确认或查询；
 * 反之未开启提议链路时，确认接口不存在，不存在「误开一个写入口」的可能。
 */
@AutoConfiguration
@ConditionalOnExpression("${zeroai.ai.enabled:false} && ${zeroai.ai.action.enabled:false}")
@EnableConfigurationProperties(ActionProposalProperties.class)
public class AiActionAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiActionAutoConfiguration.class);

    /**
     * 注册写操作提议服务。
     * <p>
     * 它是 AI 侧唯一的写入口，且只写 {@code ai_action_proposal} 提议表；
     * 订单写入仍然只能走原下单入口。
     *
     * @param proposalMapper      提议 Mapper
     * @param cartService         购物车服务
     * @param goodsProductService 商品货品服务
     * @param couponService       优惠券服务
     * @param addressService      地址服务
     * @param mobileOrderService  订单服务（原下单入口）
     * @param properties          提议配置
     * @return 提议服务
     */
    @Bean
    @ConditionalOnMissingBean
    public ActionProposalService actionProposalService(AiActionProposalMapper proposalMapper,
                                                       ICartService cartService,
                                                       IGoodsProductService goodsProductService,
                                                       ShopMemberCouponService couponService,
                                                       IAddressService addressService,
                                                       IMobileOrderService mobileOrderService,
                                                       ActionProposalProperties properties) {
        log.info("写操作提议链路已启用 ttlSeconds={} maxItems={}（Agent 仍不能直接下单，必须用户确认）",
                properties.getProposalTtlSeconds(), properties.getMaxItemsPerProposal());
        return new ActionProposalService(proposalMapper, cartService, goodsProductService,
                couponService, addressService, mobileOrderService, properties);
    }
}
