package com.zeroai.mobile.api.ai;

import com.zeroai.ai.web.AiUserContext;
import com.zeroai.mobile.framework.security.util.MobileSecurityUtils;
import org.springframework.stereotype.Component;

/**
 * AI 用户上下文实现。
 * <p>
 * 唯一数据来源是 Sa-Token 的登录上下文，<b>不读取任何请求参数或请求体</b>。
 * 必须由入口线程调用：异步回调里 ThreadLocal 已失效，
 * 因此 {@code AiChatController} 会在进入异步前先取一次快照。
 * <p>
 * 未登录时返回 null，下游据此不注册用户级工具（订单查询），从对象层面杜绝越权。
 */
@Component
public class MobileAiUserContext implements AiUserContext {

    /**
     * 获取当前登录用户 ID。
     *
     * @return 用户 ID；未登录返回 null
     */
    @Override
    public Long currentUserId() {
        return MobileSecurityUtils.getUserId();
    }
}
