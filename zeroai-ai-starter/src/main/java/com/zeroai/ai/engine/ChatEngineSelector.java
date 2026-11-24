package com.zeroai.ai.engine;

import java.util.List;

/**
 * 按配置挑选单轮引擎。
 * <p>
 * 独立成类而不是写在控制器里，是为了能在不启动 Spring 的情况下直接单测：
 * 「配置写错时挑到哪个引擎」属于容易出静默故障的分支，
 * 之前 agentscope 装配失败就是靠日志才发现降级了。
 * <p>
 * 选择规则（按顺序）：
 * <ol>
 *   <li>名字与 {@code zeroai.ai.engine} 完全一致的实现；</li>
 *   <li>没有匹配时回落到 spring-ai，保证单轮问答不会因为配置拼错而不可用；</li>
 *   <li>spring-ai 也没有时取第一个可用实现；</li>
 *   <li>一个都没有返回 null，由调用方给出明确错误。</li>
 * </ol>
 */
public final class ChatEngineSelector {

    private ChatEngineSelector() {
    }

    /**
     * 选出本次请求应使用的引擎。
     *
     * @param engines    容器中已装配的实现，可为 null 或空
     * @param configured 配置的引擎名，可为 null
     * @return 选中的引擎；一个都没有时返回 null
     */
    public static ChatStreamEngine select(List<ChatStreamEngine> engines, String configured) {
        if (engines == null || engines.isEmpty()) {
            return null;
        }
        if (configured != null && !configured.isBlank()) {
            String wanted = configured.trim();
            for (ChatStreamEngine engine : engines) {
                if (engine.name().equalsIgnoreCase(wanted)) {
                    return engine;
                }
            }
        }
        for (ChatStreamEngine engine : engines) {
            if (ChatEngineNames.SPRING_AI.equalsIgnoreCase(engine.name())) {
                return engine;
            }
        }
        return engines.get(0);
    }
}
