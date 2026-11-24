package com.zeroai.ai.engine;

/**
 * 流式回答的取消句柄。
 * <p>
 * 语义只保证「取消后不再向调用方回调」，不保证立即终止上游模型生成：
 * Spring AI 适配器基于 Reactor 订阅，{@code dispose} 会立刻断开订阅；
 * LangChain4j 1.24 的流式 API 没有公开的 abort 入口，取消后只是停止转发，
 * 上游 HTTP 流会继续跑到自然结束。因此对成本敏感的场景必须了解这一差异，见 ADR-002。
 * <p>
 * 实现必须幂等：客户端断连、超时、正常完成可能触发多次取消。
 */
@FunctionalInterface
public interface ChatStreamHandle {

    /**
     * 取消本次流式回答。幂等，可重复调用。
     */
    void cancel();

    /**
     * 空句柄，供测试与「不关心取消」的场景使用。
     *
     * @return 什么都不做的句柄
     */
    static ChatStreamHandle noop() {
        return () -> {
            // 有意为空：调用方不需要取消能力时使用
        };
    }
}
