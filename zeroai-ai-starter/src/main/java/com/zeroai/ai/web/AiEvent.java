package com.zeroai.ai.web;

/**
 * SSE 事件载体。
 * <p>
 * 前端按 {@code type} 决定渲染方式；{@code data} 一律为可序列化对象，
 * 不使用统一响应包装 R<T>——流式通道不是一次性响应。
 *
 * @param type 事件类型，取值见 {@link AiEventType}
 * @param data 事件载荷
 */
public record AiEvent(String type, Object data) {

    public static AiEvent of(String type, Object data) {
        return new AiEvent(type, data);
    }
}
