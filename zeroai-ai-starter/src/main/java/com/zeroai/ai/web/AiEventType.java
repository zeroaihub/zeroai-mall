package com.zeroai.ai.web;

/**
 * SSE 事件类型常量。
 * <p>
 * 命名与前端渲染映射一一对应，新增类型必须同步文档，禁止在业务代码里直接写裸字符串。
 */
public final class AiEventType {

    /**
     * 过程态：正在执行某一步（如「正在查库存」）。
     */
    public static final String THINKING = "thinking";

    /**
     * 工具执行结果摘要。
     */
    public static final String TOOL_RESULT = "tool_result";

    /**
     * 文本增量，前端做打字机效果。
     */
    public static final String MESSAGE_DELTA = "message_delta";

    /**
     * 商品卡片列表。
     */
    public static final String PRODUCT_CARDS = "product_cards";

    /**
     * 待用户确认的写操作提议。
     */
    public static final String CONFIRM_ACTION = "confirm_action";

    /**
     * 本轮结束，携带步数与 token 统计。
     */
    public static final String DONE = "done";

    /**
     * 降级：模型不可用时退回既有能力。
     */
    public static final String DEGRADE = "degrade";

    /**
     * 错误：携带错误码与友好文案。
     */
    public static final String ERROR = "error";

    private AiEventType() {
    }
}
