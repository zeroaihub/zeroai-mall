package com.zeroai.ai.history;

import com.zeroai.ai.config.AiHistoryProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话历史门面的行为测试。
 * <p>
 * 重点验证三件事：存档内容是否完整、匿名/无存储时是否真的空操作、
 * 以及回答超长时是否按上限截断而不是整条丢弃。
 */
class AiChatHistoryTest {

    /**
     * 内存版存储，只用于断言门面的行为，不涉及任何中间件。
     */
    private static final class MemoryStore implements AiChatHistoryStore {

        private final List<AiChatExchange> saved = new CopyOnWriteArrayList<>();

        @Override
        public void save(AiChatExchange exchange) {
            saved.add(exchange);
        }

        @Override
        public List<AiChatExchange> recent(Long userId, int size) {
            List<AiChatExchange> list = new ArrayList<>(saved);
            list.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
            return list.size() <= size ? list : list.subList(0, size);
        }

        @Override
        public int clear(Long userId) {
            int before = saved.size();
            saved.removeIf(item -> userId.equals(item.userId()));
            return before - saved.size();
        }
    }

    @Test
    void shouldSaveCompletedTurnWithToolsAndAnswer() {
        MemoryStore store = new MemoryStore();
        AiChatHistory history = new AiChatHistory(store, new AiHistoryProperties());

        AiChatHistory.Turn turn = history.begin(2001L, "u2001", "帮我看看订单");
        turn.delta("你的订单有 ");
        turn.delta("2 笔");
        turn.tool("list_my_orders");
        turn.tool("list_my_orders");
        turn.tool("other");
        turn.success(1);

        await(() -> !store.saved.isEmpty());

        AiChatExchange exchange = store.saved.getFirst();
        assertEquals(2001L, exchange.userId());
        assertEquals("u2001", exchange.sessionId());
        assertEquals("帮我看看订单", exchange.question());
        assertEquals("你的订单有 2 笔", exchange.answer());
        assertEquals(1, exchange.steps());
        assertEquals(List.of("list_my_orders"), exchange.tools(), "工具名去重且不登记未知工具");
        assertTrue(exchange.success());
    }

    @Test
    void shouldBeNoOpForAnonymousUser() {
        MemoryStore store = new MemoryStore();
        AiChatHistory history = new AiChatHistory(store, new AiHistoryProperties());

        AiChatHistory.Turn turn = history.begin(null, "anon-xxx", "随便问问");
        turn.delta("答案");
        turn.success(0);

        // 匿名会话的 sessionId 每次随机，存下来也查不回来，应当直接丢弃
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void shouldBeNoOpWhenStoreIsAbsent() {
        AiChatHistory history = new AiChatHistory(null, new AiHistoryProperties());

        AiChatHistory.Turn turn = history.begin(2001L, "u2001", "问题");
        turn.delta("答案");
        turn.success(0);

        assertTrue(history.recent(2001L, 10).isEmpty());
        assertEquals(0, history.clear(2001L));
    }

    @Test
    void shouldTruncateLongAnswerInsteadOfDroppingIt() {
        MemoryStore store = new MemoryStore();
        AiHistoryProperties properties = new AiHistoryProperties();
        properties.setMaxAnswerChars(10);
        AiChatHistory history = new AiChatHistory(store, properties);

        AiChatHistory.Turn turn = history.begin(2001L, "u2001", "长回答");
        turn.delta("0123456789ABCDEFG");
        turn.success(0);

        await(() -> !store.saved.isEmpty());
        assertEquals("0123456789", store.saved.getFirst().answer());
    }

    @Test
    void shouldNotSaveTwiceAndSkipBlankAnswer() {
        MemoryStore store = new MemoryStore();
        AiChatHistory history = new AiChatHistory(store, new AiHistoryProperties());

        AiChatHistory.Turn first = history.begin(2001L, "u2001", "首字被中断");
        first.success(0);
        first.fail("AI_STREAM_ERROR", 0);

        AiChatHistory.Turn second = history.begin(2001L, "u2001", "正常回答");
        second.delta("好的");
        second.success(0);
        second.success(0);

        await(() -> !store.saved.isEmpty());
        // 空白回答不存档，重复 success 只落一条
        assertEquals(1, store.saved.size());
        assertEquals("正常回答", store.saved.getFirst().question());
        assertFalse(store.saved.getFirst().answer().isBlank());
    }

    /**
     * 等待异步落库完成。历史写入走弹性线程池，断言前必须给它一点时间。
     *
     * @param condition 完成条件
     */
    private static void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("等待超时：历史未在 3s 内落库");
    }
}
