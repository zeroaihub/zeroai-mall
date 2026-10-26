package com.zeroai.ai.agent;

import com.zeroai.ai.web.AiEvent;
import com.zeroai.ai.web.AiEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件映射的契约测试。
 * <p>
 * 这里守的是「安全边界」而不是「转换正确性」：默认丢弃、白名单放行。
 * 一旦有人为了多显示点信息把 ThinkingBlockDeltaEvent 或 ToolCallDeltaEvent 接进来，
 * 这些用例必须失败。
 */
class AgentEventMapperTest {

    private static final String REPLY_ID = "r-1";

    private static final String CALL_ID = "c-1";

    /**
     * 卡片取值的公共工具：data 统一是 Map，避免每个用例重复强转。
     */
    private static Map<?, ?> dataOf(AiEvent event) {
        return (Map<?, ?>) event.data();
    }

    @Test
    void shouldMapTextDeltaToMessageDelta() {
        Optional<AiEvent> event = AgentEventMapper.map(new TextBlockDeltaEvent(REPLY_ID, "b-1", "这款耳机"));

        assertTrue(event.isPresent());
        assertEquals(AiEventType.MESSAGE_DELTA, event.get().type());
        assertEquals("这款耳机", dataOf(event.get()).get("delta"));
    }

    @Test
    void shouldDropBlankTextDelta() {
        assertTrue(AgentEventMapper.map(new TextBlockDeltaEvent(REPLY_ID, "b-1", "")).isEmpty());
        assertTrue(AgentEventMapper.map(new TextBlockDeltaEvent(REPLY_ID, "b-1", null)).isEmpty());
    }

    @Test
    void shouldNeverExposeReasoningChain() {
        // 推理链正文不得以任何形式下发：前端只能看到状态，不能看到模型在想什么
        Optional<AiEvent> event = AgentEventMapper.map(
                new ThinkingBlockDeltaEvent(REPLY_ID, "b-1", "用户在比价，我应该先查库存再报价"));

        assertTrue(event.isEmpty());
    }

    @Test
    void shouldMapToolCallStartToProgressWithoutArguments() {
        Optional<AiEvent> event = AgentEventMapper.map(
                new ToolCallStartEvent(REPLY_ID, CALL_ID, "search_products"));

        assertTrue(event.isPresent());
        assertEquals(AiEventType.THINKING, event.get().type());
        assertEquals("search_products", dataOf(event.get()).get("tool"));
        assertEquals("正在检索商品", dataOf(event.get()).get("message"));
        assertFalse(dataOf(event.get()).containsKey("arguments"), "工具入参不得下发");
    }

    @Test
    void shouldHideUnregisteredToolName() {
        Optional<AiEvent> event = AgentEventMapper.map(
                new ToolCallStartEvent(REPLY_ID, CALL_ID, "internal_debug_tool"));

        assertTrue(event.isPresent());
        assertEquals("other", dataOf(event.get()).get("tool"));
        assertEquals("正在处理", dataOf(event.get()).get("message"));
    }

    @Test
    void shouldDropToolCallDeltaBecauseItCarriesRawArguments() {
        Optional<AiEvent> event = AgentEventMapper.map(
                new ToolCallDeltaEvent(REPLY_ID, CALL_ID, "search_products", "{\"keyword\":\"手机\"}"));

        assertTrue(event.isEmpty());
    }

    @Test
    void shouldMapSuccessfulToolResult() {
        Optional<AiEvent> event = AgentEventMapper.map(
                new ToolResultEndEvent(REPLY_ID, CALL_ID, "get_stock_status", ToolResultState.SUCCESS));

        assertTrue(event.isPresent());
        assertEquals(AiEventType.TOOL_RESULT, event.get().type());
        assertEquals(Boolean.TRUE, dataOf(event.get()).get("success"));
        assertEquals("get_stock_status", dataOf(event.get()).get("tool"));
    }

    @Test
    void shouldMapFailedToolResult() {
        Optional<AiEvent> event = AgentEventMapper.map(
                new ToolResultEndEvent(REPLY_ID, CALL_ID, "list_my_orders", ToolResultState.ERROR));

        assertTrue(event.isPresent());
        assertEquals(Boolean.FALSE, dataOf(event.get()).get("success"));
        assertEquals("查询未返回结果", dataOf(event.get()).get("message"));
    }

    @Test
    void shouldDropEventsOutsideWhitelist() {
        assertTrue(AgentEventMapper.map(null).isEmpty());
        assertTrue(AgentEventMapper.map(new ToolCallEndEvent(REPLY_ID, CALL_ID, "search_products")).isEmpty());
    }

    @Test
    void shouldRecognizeToolCallForStepCounting() {
        assertTrue(AgentEventMapper.isToolCall(new ToolCallStartEvent(REPLY_ID, CALL_ID, "search_products")));
        assertFalse(AgentEventMapper.isToolCall(new TextBlockDeltaEvent(REPLY_ID, "b-1", "hi")));
    }
}
