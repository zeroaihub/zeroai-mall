package com.zeroai.ai.mall.proposal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 写操作提议（Human-in-the-loop）配置。
 * <p>
 * 默认关闭：提议链路会写数据库并允许最终下单，属于能力扩张，
 * 必须由接入方显式打开（{@code zeroai.ai.action.enabled=true}）。
 * 即使打开，Agent 也只能生成提议，订单写入始终走独立确认接口。
 */
@ConfigurationProperties(prefix = "zeroai.ai.action")
public class ActionProposalProperties {

    /**
     * 提议链路总开关。关闭时不注册提议工具与确认接口。
     */
    private boolean enabled = false;

    /**
     * 提议有效期（秒）。默认 10 分钟：够用户看一眼确认，又不至于拿很久之前的价格下单。
     */
    private long proposalTtlSeconds = 600L;

    /**
     * 单条提议的商品行数上限，防止模型把整张购物车塞进一条提议。
     */
    private int maxItemsPerProposal = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getProposalTtlSeconds() {
        return proposalTtlSeconds;
    }

    public void setProposalTtlSeconds(long proposalTtlSeconds) {
        this.proposalTtlSeconds = proposalTtlSeconds;
    }

    public int getMaxItemsPerProposal() {
        return maxItemsPerProposal;
    }

    public void setMaxItemsPerProposal(int maxItemsPerProposal) {
        this.maxItemsPerProposal = maxItemsPerProposal;
    }
}
