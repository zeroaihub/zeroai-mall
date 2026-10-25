package com.zeroai.ai.web;

/**
 * 当前请求用户上下文 SPI。
 * <p>
 * AI 模块不依赖任何业务鉴权框架，用户身份由接入方实现本接口提供（如 Sa-Token、Spring Security、JWT）。
 * <p>
 * 约定：实现方必须从请求上下文（ThreadLocal / SecurityContext）读取，**禁止从模型参数或请求体读取**，
 * 且必须在入口线程调用——异步线程中 ThreadLocal 已失效。
 */
public interface AiUserContext {

    /**
     * 获取当前登录用户 ID。
     *
     * @return 用户 ID；未登录或允许匿名时返回 null
     */
    Long currentUserId();
}
