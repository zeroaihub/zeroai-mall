package com.zeroai.ai.harness;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Harness 能力开关。
 * <p>
 * 设计原则：<b>每一项能力默认关闭</b>。这些能力都会扩大 Agent 的作用面
 * （读文件、写记忆、自我规划），而商城导购的最小可用集只是「四个只读工具 + 一段 system prompt」。
 * 默认关闭意味着新增能力必须由人显式打开，而不是靠「默认值恰好安全」。
 * <p>
 * 唯一例外是 {@link #observabilityEnabled}：它只读事件流、不改变 Agent 行为，
 * 且生产排障离不开它，因此默认开启。
 */
@ConfigurationProperties(prefix = "zeroai.ai.harness")
public class HarnessProperties {

    /**
     * Skills 实验开关。
     * <p>
     * 关闭时完全不加载技能仓库，模型只能看到 system prompt。
     * 开启后也只加载 {@link #allowedSkills} 白名单内的技能，
     * 并同时关闭 workspace 目录自动发现，避免「往目录里丢文件就获得新能力」。
     */
    private boolean skillsEnabled = false;

    /**
     * 允许加载的技能名白名单。
     * <p>
     * 名字必须与 SKILL.md 的 frontmatter {@code name} 一致。
     * 即使技能目录里出现其他文件，不在白名单内也不会生效。
     */
    private List<String> allowedSkills = List.of("mall-shopping-guide", "order-query-guide");

    /**
     * 技能仓库的 classpath 根目录。打包后从 jar 内读取，不依赖运行目录结构。
     */
    private String skillsLocation = "skills";

    /**
     * 长期记忆开关。
     * <p>
     * 默认关闭。开启后记忆目录按用户隔离，且写入前必须通过敏感信息守卫。
     * 注意：长期记忆只保存「用户显式表达且有必要长期保留的偏好」，
     * 不保存订单、手机号、地址、支付信息。
     */
    private boolean memoryEnabled = false;

    /**
     * 长期记忆根目录，其下按用户分子目录。已 gitignore，不得存放真实用户数据副本。
     */
    private String memoryDir = ".agentscope/memory";

    /**
     * 可观测中间件开关。只读事件流、记录耗时与 token，不改变 Agent 行为。
     */
    private boolean observabilityEnabled = true;

    /**
     * Plan Mode 开关。仅用于只读研究型任务，实验性质。
     */
    private boolean planModeEnabled = false;

    /**
     * Plan Mode 的草稿目录，与业务数据隔离。
     */
    private String planDir = ".agentscope/plans";

    /**
     * 会话状态目录。
     * <p>
     * SDK 默认把状态写到 {@code ~/.agentscope/state/<agentId>}，即用户 home 目录下。
     * 这在容器或多实例部署里既污染 home、也无法跨实例共享，因此显式改到项目内可配置路径
     * （默认落在已 gitignore 的 {@code .agentscope/state}）。
     */
    private String stateDir = ".agentscope/state";

    /**
     * 单次会话允许写入长期记忆的条目上限，防止记忆被灌爆。
     */
    private int memoryMaxEntriesPerUser = 20;

    public boolean isSkillsEnabled() {
        return skillsEnabled;
    }

    public void setSkillsEnabled(boolean skillsEnabled) {
        this.skillsEnabled = skillsEnabled;
    }

    public List<String> getAllowedSkills() {
        return allowedSkills;
    }

    public void setAllowedSkills(List<String> allowedSkills) {
        this.allowedSkills = allowedSkills;
    }

    public String getSkillsLocation() {
        return skillsLocation;
    }

    public void setSkillsLocation(String skillsLocation) {
        this.skillsLocation = skillsLocation;
    }

    public boolean isMemoryEnabled() {
        return memoryEnabled;
    }

    public void setMemoryEnabled(boolean memoryEnabled) {
        this.memoryEnabled = memoryEnabled;
    }

    public String getMemoryDir() {
        return memoryDir;
    }

    public void setMemoryDir(String memoryDir) {
        this.memoryDir = memoryDir;
    }

    public boolean isObservabilityEnabled() {
        return observabilityEnabled;
    }

    public void setObservabilityEnabled(boolean observabilityEnabled) {
        this.observabilityEnabled = observabilityEnabled;
    }

    public boolean isPlanModeEnabled() {
        return planModeEnabled;
    }

    public void setPlanModeEnabled(boolean planModeEnabled) {
        this.planModeEnabled = planModeEnabled;
    }

    public String getPlanDir() {
        return planDir;
    }

    public void setPlanDir(String planDir) {
        this.planDir = planDir;
    }

    public String getStateDir() {
        return stateDir;
    }

    public void setStateDir(String stateDir) {
        this.stateDir = stateDir;
    }

    public int getMemoryMaxEntriesPerUser() {
        return memoryMaxEntriesPerUser;
    }

    public void setMemoryMaxEntriesPerUser(int memoryMaxEntriesPerUser) {
        this.memoryMaxEntriesPerUser = memoryMaxEntriesPerUser;
    }
}
