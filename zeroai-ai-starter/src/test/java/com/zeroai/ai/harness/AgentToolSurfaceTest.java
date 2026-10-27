package com.zeroai.ai.harness;

import com.zeroai.ai.config.AiAgentProperties;
import com.zeroai.ai.agent.MallAgentContext;
import com.zeroai.ai.agent.MallAgentFactory;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具面黑名单测试。
 * <p>
 * 这里断言的不是「某个功能好用」，而是「某些工具<b>绝不能出现</b>」。
 * 这类测试的价值在于回归防护：SDK 升级后若把某个危险工具默认打开，
 * 或某个开关失效，这里会立刻红掉。
 */
class AgentToolSurfaceTest {

    private static HarnessAgent build(Path workspace, boolean skillsEnabled) {
        AiAgentProperties properties = new AiAgentProperties();
        properties.setApiKey("sk-test-not-a-real-key");
        properties.setBaseUrl("https://example.invalid");
        properties.setModel("test-model");
        properties.setAgentWorkspace(workspace.toString());

        HarnessProperties harness = new HarnessProperties();
        harness.setSkillsEnabled(skillsEnabled);
        harness.setObservabilityEnabled(true);

        return new MallAgentFactory(properties, harness, null, null, null, null,
                null, null, null, new com.zeroai.ai.mall.proposal.ActionProposalProperties(), null)
                .create(new MallAgentContext(1001L, "u1001"));
    }

    /** 模型绝不能拥有的工具：写文件、执行命令、改技能、改记忆、起子 Agent。 */
    private static final Set<String> FORBIDDEN = Set.of(
            "shell", "shell_execute", "execute_command",
            "write_file", "read_file", "edit_file", "list_dir",
            "skill_manage", "propose_skill",
            "memory_save", "memory_get", "memory_search", "session_search",
            "agent_spawn", "agent_generate", "team_task",
            "web_fetch", "web_search", "http_request");

    @Test
    @DisplayName("技能关闭时工具面最小")
    void minimalToolSurfaceWhenSkillsOff(@TempDir Path workspace) {
        try (HarnessAgent agent = build(workspace, false)) {
            Set<String> names = agent.getDelegate().getToolkit().getToolNames();
            Set<String> leaked = names.stream().filter(FORBIDDEN::contains).collect(java.util.stream.Collectors.toSet());
            assertTrue(leaked.isEmpty(), "出现禁止工具：" + leaked + " 全量=" + names);
        }
    }

    @Test
    @DisplayName("技能开启后仍不得出现技能管理类工具")
    void noSkillManagementToolsWhenSkillsOn(@TempDir Path workspace) {
        try (HarnessAgent agent = build(workspace, true)) {
            Set<String> names = agent.getDelegate().getToolkit().getToolNames();
            assertFalse(names.contains("skill_manage"),
                    "skill_manage 允许模型自建技能，必须关闭：全量=" + names);
            assertFalse(names.contains("propose_skill"),
                    "propose_skill 允许模型提交新技能，必须关闭：全量=" + names);
            assertFalse(names.contains("agent_spawn"), "不得允许创建子 Agent：全量=" + names);
            assertFalse(names.contains("shell"), "不得允许执行命令：全量=" + names);
        }
    }

    @Test
    @DisplayName("技能开启时只多出技能读取工具，不多出写能力")
    void skillsAddOnlyReadTool(@TempDir Path workspace) {
        Set<String> off;
        try (HarnessAgent agent = build(workspace.resolve("off"), false)) {
            off = agent.getDelegate().getToolkit().getToolNames();
        }
        Set<String> on;
        try (HarnessAgent agent = build(workspace.resolve("on"), true)) {
            on = agent.getDelegate().getToolkit().getToolNames();
        }
        Set<String> added = new java.util.HashSet<>(on);
        added.removeAll(off);
        assertTrue(added.stream().noneMatch(t -> t.contains("manage") || t.contains("propose")),
                "开启技能不得引入管理类工具，实际新增=" + added);
    }
}
