package com.zeroai.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对话历史配置。
 * <p>
 * 历史是<b>旁路能力</b>：关闭或存储不可用时，对话必须照常工作。
 * 因此这里没有「必需项」，最坏情况就是查询返回空列表。
 */
@ConfigurationProperties(prefix = "zeroai.ai.history")
public class AiHistoryProperties {

    /**
     * 是否记录对话历史。关闭后既不写也不读，页面只能看到当次会话。
     */
    private boolean enabled = true;

    /**
     * 存储位置标识。对 ES 实现是索引名，对其他实现可解释为表名或目录名。
     */
    private String index = "zeroai_ai_chat";

    /**
     * 单条回答最多保存的字符数。超长回答按前缀截断，避免把整篇长文塞进存储。
     */
    private int maxAnswerChars = 8000;

    /**
     * 查询历史时的默认条数。
     */
    private int defaultPageSize = 20;

    /**
     * 查询历史时允许的最大条数，防止前端传个超大值把存储打满。
     */
    private int maxPageSize = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIndex() {
        return index;
    }

    public void setIndex(String index) {
        this.index = index;
    }

    public int getMaxAnswerChars() {
        return maxAnswerChars;
    }

    public void setMaxAnswerChars(int maxAnswerChars) {
        this.maxAnswerChars = maxAnswerChars;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }
}
