package com.zeroai.ai.history;

import java.util.List;

/**
 * 对话历史存储 SPI。
 * <p>
 * 本模块<b>不绑定任何存储</b>：ES、MySQL、对象存储都只是实现之一，
 * 由入口模块按自身技术栈提供实现并注册为 Bean。
 * 没有实现时 {@link AiChatHistory} 退化为空操作，对话链路不受影响。
 * <p>
 * 实现方必须遵守的约定：
 * <ul>
 *   <li>所有读写<b>必须带 userId 过滤</b>，禁止提供「按记录 ID 直接取全文」的入口；</li>
 *   <li>保存与查询都不得抛异常到调用方——历史是旁路能力，坏掉不能影响对话；</li>
 *   <li>answer 可能是长文本，实现方需要按自身字段上限截断而不是整条丢弃。</li>
 * </ul>
 */
public interface AiChatHistoryStore {

    /**
     * 保存一轮问答。
     *
     * @param exchange 已完成的问答记录，非 null
     */
    void save(AiChatExchange exchange);

    /**
     * 查询某用户最近的记录，按时间倒序。
     *
     * @param userId 用户 ID，非 null
     * @param size   条数上限
     * @return 记录列表，无记录时返回空列表
     */
    List<AiChatExchange> recent(Long userId, int size);

    /**
     * 清空某用户的全部记录。
     *
     * @param userId 用户 ID，非 null
     * @return 删除条数，实现不支持统计时返回 0
     */
    int clear(Long userId);
}
