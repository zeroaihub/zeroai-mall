package com.zeroai.ai.agent;

import com.zeroai.ai.web.AiEvent;
import com.zeroai.ai.web.AiEventType;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;

import java.util.Map;
import java.util.Optional;

/**
 * AgentScope 事件到 SSE 协议的转换器。
 * <p>
 * 设计原则（对应实施方案 Phase 4「安全边界」）：
 * <ul>
 *   <li><b>只输出白名单事件</b>：未列出的 AgentScope 事件一律丢弃，默认拒绝而不是默认放行。
 *       这样 SDK 版本升级新增事件时，不会因为未做处理就把内部细节泄漏到前端。</li>
 *   <li><b>不输出推理链</b>：{@code ThinkingBlockDeltaEvent} 携带模型思考正文，直接丢弃；
 *       前端只会看到「正在处理」这类状态，看不到模型内部推理。</li>
 *   <li><b>不输出工具原始参数</b>：{@code ToolCallDeltaEvent} / {@code ToolCallEndEvent}
 *       含工具入参增量，直接丢弃；工具进度只暴露白名单内的工具名与固定中文文案。</li>
 * </ul>
 * 本类无状态，可安全复用为单例；步数统计等有状态逻辑由 {@link AgentChatHandler} 承担。
 */
public final class AgentEventMapper {

    /**
     * 工具名到进度文案的映射。
     * <p>
     * 只登记本模块实际注册的只读工具。未登记的工具名统一降级为「正在处理」，
     * 且不把原始名称回传前端——避免未来新增内部工具时名字意外暴露。
     */
    private static final Map<String, String> TOOL_PROGRESS = Map.of(
            "search_products", "正在检索商品",
            "get_product_detail", "正在查看商品详情",
            "get_stock_status", "正在确认库存",
            "search_knowledge", "正在查商城规则",
            "list_my_orders", "正在查询你的订单",
            "list_my_addresses", "正在核对收货地址",
            "list_my_cart", "正在查看购物车",
            "propose_order", "正在生成下单提议"
    );

    /** 未知工具的统一对外标识，不含任何内部命名信息。 */
    private static final String UNKNOWN_TOOL = "other";

    /** 未知工具的兜底进度文案。 */
    private static final String DEFAULT_PROGRESS = "正在处理";

    private AgentEventMapper() {
    }

    /**
     * 把单个 AgentScope 事件转换为 SSE 事件。
     *
     * @param event AgentScope 事件，允许为 null
     * @return 需要下发的 SSE 事件；该事件不应对外暴露时返回空
     */
    public static Optional<AiEvent> map(AgentEvent event) {
        if (event == null) {
            return Optional.empty();
        }
        if (event instanceof TextBlockDeltaEvent textDelta) {
            String delta = textDelta.getDelta();
            if (delta == null || delta.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(AiEvent.of(AiEventType.MESSAGE_DELTA, Map.of("delta", delta)));
        }
        if (event instanceof ToolCallStartEvent toolStart) {
            String toolName = toolStart.getToolCallName();
            return Optional.of(AiEvent.of(AiEventType.THINKING, Map.of(
                    "status", "running",
                    "tool", safeToolLabel(toolName),
                    "message", progressOf(toolName))));
        }
        if (event instanceof ToolResultEndEvent toolResult) {
            boolean success = toolResult.getState() == ToolResultState.SUCCESS;
            return Optional.of(AiEvent.of(AiEventType.TOOL_RESULT, Map.of(
                    "tool", safeToolLabel(toolResult.getToolCallName()),
                    "success", success,
                    "message", success ? "查询完成" : "查询未返回结果")));
        }
        // 其余事件（含 ThinkingBlockDelta / ToolCallDelta / 各类内部块事件）显式丢弃
        return Optional.empty();
    }

    /**
     * 判断该事件是否代表一次工具调用，用于步数统计。
     *
     * @param event AgentScope 事件
     * @return 工具调用开始时返回 true
     */
    public static boolean isToolCall(AgentEvent event) {
        return event instanceof ToolCallStartEvent;
    }

    /**
     * 白名单工具名对外标识。未登记的工具不回落为原始名称。
     *
     * @param toolName SDK 给出的工具名，可为 null
     * @return 可安全下发的工具标识
     */
    private static String safeToolLabel(String toolName) {
        if (toolName == null || !TOOL_PROGRESS.containsKey(toolName)) {
            return UNKNOWN_TOOL;
        }
        return toolName;
    }

    /**
     * 工具执行中的进度文案。
     *
     * @param toolName SDK 给出的工具名，可为 null
     * @return 固定中文文案
     */
    private static String progressOf(String toolName) {
        if (toolName == null) {
            return DEFAULT_PROGRESS;
        }
        return TOOL_PROGRESS.getOrDefault(toolName, DEFAULT_PROGRESS);
    }
}
