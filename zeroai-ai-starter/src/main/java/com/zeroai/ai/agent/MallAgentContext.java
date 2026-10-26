package com.zeroai.ai.agent;

/**
 * 一次 Agent 调用的上下文。
 * <p>
 * {@code userId} 必须是服务端从鉴权上下文取到的可信值，禁止来自请求参数或模型输出。
 * {@code sessionId} 仅用于会话连续性（AgentScope 的会话恢复键），
 * <b>不作为授权凭据</b>：工具的数据范围永远由 {@code userId} 决定，
 * 换掉 sessionId 无法看到别人的数据。
 *
 * @param userId    当前登录用户 ID，可为 null（匿名，此时不注册任何用户级工具）
 * @param sessionId 会话标识，用于多轮上下文延续
 */
public record MallAgentContext(Long userId, String sessionId) {
}
