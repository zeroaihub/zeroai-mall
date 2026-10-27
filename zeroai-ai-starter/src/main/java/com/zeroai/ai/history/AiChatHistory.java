package com.zeroai.ai.history;

import com.zeroai.ai.config.AiHistoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话历史门面：把「一轮对话」聚合成一条记录，并在结束后异步落库。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>旁路不添乱</b>：存储为 null、开关关闭或用户匿名时，{@link #begin} 返回空操作实例，
 *       调用方无需到处判空；落库在线程池里做，异常只写日志，绝不回传给对话链路。</li>
 *   <li><b>结束即存档</b>：只在 done / error 时落一次，中途断连不落库，
 *       避免把半句话当成完整回答存进去。</li>
 *   <li><b>按用户隔离</b>：{@code userId} 为空（匿名会话）直接丢弃——
 *       匿名会话的 sessionId 每次随机，存下来也查不回来，只会变成垃圾数据。</li>
 * </ul>
 * 线程模型：{@code Turn} 的写方法由同一事件流线程调用，内部加锁只为兜住
 * 「断连回调与事件线程并发」这一种情况。
 */
public class AiChatHistory {

    private static final Logger log = LoggerFactory.getLogger(AiChatHistory.class);

    /** 时间格式与 ES date 的 strict_date_optional_time 兼容，跨存储也通用。 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** 单条记录最多登记的工具名，防止异常情况下无上限增长。 */
    private static final int MAX_TOOLS = 20;

    private final AiChatHistoryStore store;
    private final AiHistoryProperties properties;

    /**
     * @param store      存储实现，可为 null（表示入口模块未提供存储）
     * @param properties 历史配置，可为 null 时使用默认值
     */
    public AiChatHistory(AiChatHistoryStore store, AiHistoryProperties properties) {
        this.store = store;
        this.properties = properties == null ? new AiHistoryProperties() : properties;
        if (store == null && this.properties.isEnabled()) {
            log.info("对话历史已开启但未提供 AiChatHistoryStore 实现，历史能力按空操作处理（对话不受影响）");
        }
    }

    /**
     * 开始记录一轮对话。
     *
     * @param userId    当前用户 ID，可为 null
     * @param sessionId 会话标识
     * @param question  用户问题
     * @return 轮次记录器；不可记录时返回空操作实例，永不为 null
     */
    public Turn begin(Long userId, String sessionId, String question) {
        boolean recordable = store != null && properties.isEnabled() && userId != null;
        return new Turn(recordable ? store : null,
                recordable ? properties.getMaxAnswerChars() : 0,
                userId, sessionId, question);
    }

    /**
     * 查询最近的历史记录。
     *
     * @param userId 用户 ID，可为 null
     * @param limit  期望条数，小于等于 0 时用默认值
     * @return 记录列表，按时间倒序；不可用时返回空列表
     */
    public List<AiChatExchange> recent(Long userId, int limit) {
        if (store == null || !properties.isEnabled() || userId == null) {
            return List.of();
        }
        int size = limit <= 0 ? properties.getDefaultPageSize()
                : Math.min(limit, properties.getMaxPageSize());
        try {
            List<AiChatExchange> list = store.recent(userId, size);
            return list == null ? List.of() : list;
        } catch (RuntimeException e) {
            log.warn("查询对话历史失败，按无历史返回：userId={} error={}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 清空某用户的全部历史。
     *
     * @param userId 用户 ID
     * @return 删除条数；不可用时返回 0
     */
    public int clear(Long userId) {
        if (store == null || !properties.isEnabled() || userId == null) {
            return 0;
        }
        try {
            return store.clear(userId);
        } catch (RuntimeException e) {
            log.warn("清空对话历史失败：userId={} error={}", userId, e.getMessage());
            return 0;
        }
    }

    /**
     * 单轮对话记录器。所有方法幂等、空操作安全，调用方不需要判空。
     */
    public static final class Turn {

        private final AiChatHistoryStore store;
        private final int maxAnswerChars;
        private final Long userId;
        private final String sessionId;
        private final String question;
        private final StringBuilder answer = new StringBuilder();
        private final Set<String> tools = new LinkedHashSet<>();
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private Turn(AiChatHistoryStore store, int maxAnswerChars,
                     Long userId, String sessionId, String question) {
            this.store = store;
            this.maxAnswerChars = maxAnswerChars;
            this.userId = userId;
            this.sessionId = sessionId;
            this.question = question;
        }

        /**
         * 追加一段文本增量。
         *
         * @param delta 模型输出增量，可为 null
         */
        public void delta(String delta) {
            if (store == null || delta == null || delta.isEmpty()) {
                return;
            }
            synchronized (answer) {
                if (answer.length() >= maxAnswerChars) {
                    return;
                }
                int room = maxAnswerChars - answer.length();
                answer.append(delta.length() <= room ? delta : delta.substring(0, room));
            }
        }

        /**
         * 登记一次工具调用。
         *
         * @param tool 工具名，可为 null（未知工具不登记）
         */
        public void tool(String tool) {
            if (store == null || tool == null || tool.isEmpty() || "other".equals(tool)) {
                return;
            }
            synchronized (answer) {
                if (tools.size() < MAX_TOOLS) {
                    tools.add(tool);
                }
            }
        }

        /**
         * 本轮正常结束。
         *
         * @param steps 工具调用步数
         */
        public void success(int steps) {
            finish(steps, true, null);
        }

        /**
         * 本轮失败结束。
         *
         * @param errorCode 稳定错误码
         * @param steps     工具调用步数
         */
        public void fail(String errorCode, int steps) {
            finish(steps, false, errorCode);
        }

        /**
         * 落库一次，重复调用直接忽略。
         *
         * @param steps     工具调用步数
         * @param ok        是否成功
         * @param errorCode 失败错误码
         */
        private void finish(int steps, boolean ok, String errorCode) {
            if (store == null || !finished.compareAndSet(false, true)) {
                return;
            }
            String text;
            List<String> calledTools;
            synchronized (answer) {
                text = answer.toString();
                calledTools = Collections.unmodifiableList(new ArrayList<>(tools));
            }
            // 没有任何内容的「空气泡」（如首字就被中断）不存档，避免历史里出现空白条目
            if (text.isBlank() && ok) {
                return;
            }
            AiChatExchange exchange = new AiChatExchange(
                    UUID.randomUUID().toString(),
                    userId,
                    sessionId,
                    question,
                    text,
                    steps,
                    calledTools,
                    ok,
                    errorCode,
                    LocalDateTime.now().format(TIME_FORMATTER));

            Schedulers.boundedElastic().schedule(() -> {
                try {
                    store.save(exchange);
                } catch (RuntimeException e) {
                    // 历史属于旁路能力：写失败只记日志，不影响已经完成的对话
                    log.warn("对话历史写入失败，已忽略：userId={} error={}", userId, e.getMessage());
                }
            });
        }
    }
}
