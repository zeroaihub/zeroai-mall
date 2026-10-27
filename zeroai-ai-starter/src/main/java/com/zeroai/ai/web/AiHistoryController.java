package com.zeroai.ai.web;

import com.zeroai.ai.history.AiChatExchange;
import com.zeroai.ai.history.AiChatHistory;
import com.zeroai.util.util.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对话历史查询与清空入口。
 * <p>
 * 只暴露<b>当前登录用户自己的</b>历史：userId 只从 {@link AiUserContext} 取，
 * 不接受请求参数里的用户标识，因此不存在「换个 ID 看别人聊了什么」的入口。
 * <p>
 * 返回结构刻意裁掉了 sessionId / userId：前端只需要「问过什么、答了什么」，
 * 会话标识属于服务端内部概念，没必要下发。
 */
@RestController
@RequestMapping("ai/history")
@ConditionalOnExpression("${zeroai.ai.enabled:false} && ${zeroai.ai.history.enabled:true}")
public class AiHistoryController {

    private static final Logger log = LoggerFactory.getLogger(AiHistoryController.class);

    private final AiChatHistory history;
    private final AiUserContext userContext;

    /**
     * @param history     历史门面
     * @param userContext 用户上下文，由宿主实现
     */
    public AiHistoryController(AiChatHistory history, AiUserContext userContext) {
        this.history = history;
        this.userContext = userContext;
    }

    /**
     * 查询当前用户最近的对话记录，按时间倒序。
     *
     * @param limit 条数，服务端会按 {@code zeroai.ai.history.max-page-size} 封顶
     * @return 历史记录列表
     */
    @GetMapping
    public R<List<HistoryItem>> list(@RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        Long userId = userContext.currentUserId();
        if (userId == null) {
            // 未登录没有可查询的历史，返回空列表而不是 401，避免前端首屏弹登录框
            return R.success(List.of());
        }
        List<AiChatExchange> exchanges = history.recent(userId, limit);
        List<HistoryItem> items = exchanges.stream().map(HistoryItem::of).toList();
        log.debug("查询 AI 历史 userId={} limit={} size={}", userId, limit, items.size());
        return R.success(items);
    }

    /**
     * 清空当前用户的全部历史记录。
     * <p>
     * 只删除本用户记录；不影响其他用户，也不影响 Agent 自身的会话状态。
     *
     * @return 删除条数
     */
    @DeleteMapping
    public R<Integer> clear() {
        Long userId = userContext.currentUserId();
        if (userId == null) {
            return R.success(0);
        }
        int removed = history.clear(userId);
        log.info("清空 AI 历史 userId={} removed={}", userId, removed);
        return R.success(removed);
    }

    /**
     * 对外历史条目。只含用户可感知的字段。
     *
     * @param id        记录 ID
     * @param question  用户问题
     * @param answer    模型回答
     * @param steps     工具调用步数
     * @param tools     调用过的工具名
     * @param success   是否正常结束
     * @param createdAt 记录时间
     */
    public record HistoryItem(String id,
                              String question,
                              String answer,
                              int steps,
                              List<String> tools,
                              boolean success,
                              String createdAt) {

        /**
         * 从存档对象裁剪出对外视图。
         *
         * @param exchange 存档记录
         * @return 对外条目
         */
        static HistoryItem of(AiChatExchange exchange) {
            return new HistoryItem(exchange.id(), exchange.question(), exchange.answer(),
                    exchange.steps(), exchange.tools(), exchange.success(), exchange.createdAt());
        }
    }
}
