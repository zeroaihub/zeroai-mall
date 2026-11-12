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

    /**
     * 确认时等待「消费端落单结果」的最长时间（毫秒）。
     * <p>
     * 下单是异步的：入口只投递消息，真正落库在消息消费端。
     * 确认接口在这个窗口内轮询结果缓存，能拿到结果就把「已确认」或「失败原因」一次性告诉用户；
     * 拿不到则保持「处理中」，由前端继续轮询提议状态。
     * 设为 0 表示不等待，直接按「处理中」返回（测试里常用）。
     */
    private long resultWaitMs = 2000L;

    /**
     * 等待落单结果时的轮询间隔（毫秒）。太小会空转 Redis，太大则拖长确认耗时。
     */
    private long resultPollIntervalMs = 100L;

    /**
     * 「处理中」提议的兜底超时（秒）。
     * <p>
     * 结果缓存只有 60 秒生命周期，消费端彻底不工作时不会有任何结果写入；
     * 超过该时间仍是 CONFIRMING 的提议按超时拒绝，避免前端永远转圈。
     */
    private long confirmTimeoutSeconds = 120L;

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

    public long getResultWaitMs() {
        return resultWaitMs;
    }

    public void setResultWaitMs(long resultWaitMs) {
        this.resultWaitMs = resultWaitMs;
    }

    public long getResultPollIntervalMs() {
        return resultPollIntervalMs;
    }

    public void setResultPollIntervalMs(long resultPollIntervalMs) {
        this.resultPollIntervalMs = resultPollIntervalMs;
    }

    public long getConfirmTimeoutSeconds() {
        return confirmTimeoutSeconds;
    }

    public void setConfirmTimeoutSeconds(long confirmTimeoutSeconds) {
        this.confirmTimeoutSeconds = confirmTimeoutSeconds;
    }
}
