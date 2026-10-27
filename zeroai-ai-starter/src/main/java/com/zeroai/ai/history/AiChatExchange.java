package com.zeroai.ai.history;

import java.util.List;

/**
 * 一轮已完成的 AI 问答记录。
 * <p>
 * 这是<b>存档对象</b>，不参与模型上下文：多轮上下文仍由 Agent 自身状态承担。
 * 之所以单独留一份可检索的记录，是为了让用户重进页面能看到历史、
 * 让运营侧能统计「问了什么、调了哪些工具、失败在哪一步」。
 * <p>
 * 隐私约定：只记录用户自己输入的文本与模型回答，不记录工具入参、订单号明细、
 * 手机号或地址；{@code userId} 只用于按用户隔离检索。
 *
 * @param id        记录 ID，落库实现自行生成（ES 文档 ID）
 * @param userId    归属用户 ID，匿名会话为 null 且不会被存档
 * @param sessionId 会话标识，与 Agent 会话一致
 * @param question  用户原话
 * @param answer    模型回答全文
 * @param steps     本轮工具调用步数
 * @param tools     本轮调用过的工具名（去重、保序）
 * @param success   本轮是否正常结束
 * @param errorCode 失败时的稳定错误码，成功为 null
 * @param createdAt 记录时间，ISO-8601 本地时间字符串
 */
public record AiChatExchange(String id,
                             Long userId,
                             String sessionId,
                             String question,
                             String answer,
                             int steps,
                             List<String> tools,
                             boolean success,
                             String errorCode,
                             String createdAt) {
}
