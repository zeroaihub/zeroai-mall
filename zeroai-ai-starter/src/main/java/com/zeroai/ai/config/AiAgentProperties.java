package com.zeroai.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI Agent 通用配置。
 * <p>
 * 该配置只承载与业务无关的能力开关和全局闸门（步数、超时、历史轮数），
 * 业务侧通过 SPI 注册工具后，无需新增配置项。
 */
@ConfigurationProperties(prefix = "zeroai.ai")
public class AiAgentProperties {

    /**
     * AI 能力总开关。关闭时不注册任何 AI Bean，应用行为与接入前完全一致。
     */
    private boolean enabled = false;

    /**
     * 模型通道：openai（OpenAI 兼容协议，DeepSeek/通义等均可用）。
     */
    private String provider = "openai";

    /**
     * 引擎选择，决定 {@code /ai/chat} 走哪条实现：
     * <ul>
     *   <li>{@code spring-ai} —— 默认，零工具单轮问答，复用现有 ChatClient，行为与接入前完全一致。</li>
     *   <li>{@code agentscope} —— 启用 AgentScope Harness，可调用只读商城工具。</li>
     * </ul>
     * 两条通道共用 baseUrl / apiKey / model，不引入第二套密钥约定。
     * 切换引擎不改变任何前端契约，SSE 事件协议保持一致。
     */
    private String engine = "spring-ai";

    /**
     * AgentScope 工作目录，必须可写。默认落在运行目录下的 .agentscope/workspace（已 gitignore）。
     */
    private String agentWorkspace = ".agentscope/workspace";

    /**
     * Agent 通道的 System Prompt 位置。与 spring-ai 通道的 systemPrompt 分开，
     * 因为 Agent 需要额外声明「只读工具边界」与「禁止代替用户下单」，
     * 单轮问答通道则不需要这些约束。
     */
    private String agentSystemPrompt = "prompts/mall-agent-system.st";

    /**
     * 模型服务地址，OpenAI 兼容端点。
     */
    private String baseUrl = "https://api.deepseek.com";

    /**
     * 模型密钥，必须通过环境变量注入，不允许写入配置文件。
     */
    private String apiKey;

    /**
     * 模型名称。
     */
    private String model = "deepseek-flash";

    /**
     * 采样温度，导购场景建议 0.3，降低随机性。
     */
    private Double temperature = 0.3D;

    /**
     * 单次回答最大 token，防止成本失控。
     */
    private Integer maxTokens = 1024;

    /**
     * System Prompt 的 classpath 位置，外置便于版本化与回归。
     */
    private String systemPrompt = "prompts/guide-system.st";

    /**
     * 流式对话端点路径。
     */
    private String chatPath = "/ai/chat";

    /**
     * SSE 连接超时，0 表示不超时（由容器决定）。默认 2 分钟，避免长回答被容器默认 30s 掐断。
     */
    private Long sseTimeoutMs = 120_000L;

    /**
     * 单轮最大工具调用步数，防死循环。
     */
    private Integer maxSteps = 6;

    /**
     * 单轮工具调用次数上限，与 maxSteps 共同封顶。
     */
    private Integer maxToolCallsPerTurn = 6;

    /**
     * 进入上下文的历史轮数。
     */
    private Integer maxHistoryTurns = 8;

    /**
     * 单个工具执行超时时间，超时转为 observation 回喂模型。
     */
    private Long toolTimeoutMs = 3_000L;

    /**
     * 模型异常时是否降级到既有能力（普通搜索）。
     */
    private boolean degradeOnError = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public String getAgentWorkspace() {
        return agentWorkspace;
    }

    public void setAgentWorkspace(String agentWorkspace) {
        this.agentWorkspace = agentWorkspace;
    }

    public String getAgentSystemPrompt() {
        return agentSystemPrompt;
    }

    public void setAgentSystemPrompt(String agentSystemPrompt) {
        this.agentSystemPrompt = agentSystemPrompt;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getChatPath() {
        return chatPath;
    }

    public void setChatPath(String chatPath) {
        this.chatPath = chatPath;
    }

    public Long getSseTimeoutMs() {
        return sseTimeoutMs;
    }

    public void setSseTimeoutMs(Long sseTimeoutMs) {
        this.sseTimeoutMs = sseTimeoutMs;
    }

    public Integer getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(Integer maxSteps) {
        this.maxSteps = maxSteps;
    }

    public Integer getMaxToolCallsPerTurn() {
        return maxToolCallsPerTurn;
    }

    public void setMaxToolCallsPerTurn(Integer maxToolCallsPerTurn) {
        this.maxToolCallsPerTurn = maxToolCallsPerTurn;
    }

    public Integer getMaxHistoryTurns() {
        return maxHistoryTurns;
    }

    public void setMaxHistoryTurns(Integer maxHistoryTurns) {
        this.maxHistoryTurns = maxHistoryTurns;
    }

    public Long getToolTimeoutMs() {
        return toolTimeoutMs;
    }

    public void setToolTimeoutMs(Long toolTimeoutMs) {
        this.toolTimeoutMs = toolTimeoutMs;
    }

    public boolean isDegradeOnError() {
        return degradeOnError;
    }

    public void setDegradeOnError(boolean degradeOnError) {
        this.degradeOnError = degradeOnError;
    }
}
