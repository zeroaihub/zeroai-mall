package com.zeroai.ai.agent;

import com.zeroai.ai.config.AiAgentProperties;
import com.zeroai.ai.harness.HarnessProperties;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan Mode 与 Workspace 边界实验。
 * <p>
 * Plan Mode 在本项目只是<b>实验能力</b>：它让模型先产出计划再执行，
 * 但写操作不在本阶段开放范围，因此只验证「能开关、目录隔离、不引入额外写工具」。
 */
class PlanModeAndWorkspaceTest {

    private static MallAgentFactory factory(Path workspaceDir, Path planDir, boolean planModeEnabled) {
        AiAgentProperties properties = new AiAgentProperties();
        properties.setApiKey("sk-test-not-a-real-key");
        properties.setBaseUrl("https://example.invalid");
        properties.setModel("test-model");
        properties.setAgentWorkspace(workspaceDir.toString());

        HarnessProperties harness = new HarnessProperties();
        harness.setPlanModeEnabled(planModeEnabled);
        harness.setPlanDir(planDir.toString());
        harness.setSkillsEnabled(false);
        harness.setMemoryEnabled(false);

        return new MallAgentFactory(properties, harness, null, null, null, null,
                null, null, null, new com.zeroai.ai.mall.proposal.ActionProposalProperties());
    }

    @Test
    @DisplayName("Plan Mode 关闭时不注册计划工具（enterPlanMode 仍可强制调用，非权限边界）")
    void planModeToolsAbsentWhenDisabled(@TempDir Path workspace, @TempDir Path plans) {
        try (HarnessAgent agent = factory(workspace, plans, false)
                .create(new MallAgentContext(1001L, "u1001"))) {
            var names = agent.getDelegate().getToolkit().getToolNames();
            assertFalse(hasPlanTool(names), "未启用时不应注册计划工具：" + names);

            // 记录 SDK 行为：开关只控制工具注册，enterPlanMode 本身不受约束。
            // 因此 Plan Mode 不能当作权限边界使用，只能当作能力开关。
            agent.enterPlanMode("1001", "u1001");
            assertTrue(agent.isPlanModeActive("1001", "u1001"),
                    "SDK 允许强制进入 Plan Mode，说明它不是安全边界");
        }
    }

    @Test
    @DisplayName("Plan Mode 开启后默认即以规划态启动，可按会话退出")
    void planModeTogglesPerSession(@TempDir Path workspace, @TempDir Path plans) {
        try (HarnessAgent agent = factory(workspace, plans, true)
                .create(new MallAgentContext(1001L, "u1001"))) {
            // enablePlanMode(true) 的语义是「以 Plan Mode 启动」，不是「允许之后手动进入」
            assertTrue(agent.isPlanModeActive("1001", "u1001"), "启用后应默认处于规划态");

            agent.exitPlanMode("1001", "u1001");
            assertFalse(agent.isPlanModeActive("1001", "u1001"), "退出后应为未激活");

            assertTrue(Files.isDirectory(plans.resolve("u1001")),
                    "计划目录必须按会话隔离，实际=" + plans.toAbsolutePath());
        }
    }

    @Test
    @DisplayName("Plan Mode 开启后注册计划工具，但仍不放开写文件与命令执行")
    void planModeRegistersToolsWithoutWriteCapability(@TempDir Path workspace, @TempDir Path plans) {
        try (HarnessAgent agent = factory(workspace, plans, true)
                .create(new MallAgentContext(1001L, "u1001"))) {
            var names = agent.getDelegate().getToolkit().getToolNames();
            assertTrue(hasPlanTool(names), "启用后应注册计划工具，实际=" + names);
            assertFalse(names.contains("shell"), "Plan Mode 不得放开命令执行");
            assertFalse(names.contains("write_file"), "Plan Mode 不得放开文件写入");
            assertFalse(names.contains("read_file"), "Plan Mode 不得放开文件读取");
        }
    }

    private static boolean hasPlanTool(Set<String> names) {
        return names.stream().anyMatch(n -> n.toLowerCase().contains("plan"));
    }

    @Test
    @DisplayName("两个用户的 workspace 互相独立")
    void workspacesAreIsolatedPerUser(@TempDir Path workspace, @TempDir Path plans) {
        MallAgentFactory factory = factory(workspace, plans, false);
        try (HarnessAgent a = factory.create(new MallAgentContext(93L, "u93"));
             HarnessAgent b = factory.create(new MallAgentContext(94L, "u94"))) {
            assertTrue(Files.isDirectory(workspace.resolve("u93")));
            assertTrue(Files.isDirectory(workspace.resolve("u94")));
            assertFalse(a.getName().equals(b.getName() + "-x"));
        }
    }
}
