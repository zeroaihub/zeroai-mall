package com.zeroai.ai.harness;

import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具面策略：把模型能看到的工具收敛到「显式白名单 + 强制黑名单」的交集。
 * <p>
 * 为什么需要这一层：AgentScope 的 Builder 开关并不覆盖全部工具注册路径。
 * 实测在关闭 filesystem / shell / web / memory / subagents 之后，
 * {@code skill_manage} 与 {@code propose_skill} 仍会随技能框架一起注册进来——
 * 前者允许模型<b>自行创建和修改技能文件</b>，等于绕过「技能只来自 classpath 白名单」这一前提。
 * <p>
 * 因此这里做最后一道收敛：构建完成后按名字剔除。剔除是<b>幂等</b>的，
 * 工具不存在时静默跳过，不依赖 SDK 内部实现细节。
 */
public final class ToolSurfacePolicy {

    private static final Logger log = LoggerFactory.getLogger(ToolSurfacePolicy.class);

    /**
     * 强制黑名单。命中即从工具集中移除，且移除会记日志以便审计。
     * <p>
     * 这些工具的共同点：都具备「写仓库 / 改自身能力 / 触达外部」的性质，
     * 而商城导购只需要四个只读查询工具。
     */
    private static final Set<String> DENY = new LinkedHashSet<>(List.of(
            // 技能自演进：允许模型创建、改写、删除技能，等于自我提权
            "skill_manage", "propose_skill",
            // 记忆读写：写入参数由模型自由生成，无法满足「用户明确允许」
            "memory_save", "memory_get", "memory_search", "session_search",
            // 宿主机能力
            "shell", "shell_execute", "execute_command",
            "read_file", "write_file", "edit_file", "list_dir", "glob", "grep",
            // 子 Agent 与团队协作
            "agent_spawn", "agent_generate", "team_task", "team_message",
            // 外部网络
            "web_fetch", "web_search", "http_request"
    ));

    private ToolSurfacePolicy() {
    }

    /**
     * 按黑名单收敛工具集。
     *
     * @param toolkit 已装配完毕的工具集
     * @return 实际被移除的工具名，便于日志与测试断言
     */
    public static List<String> enforce(Toolkit toolkit) {
        if (toolkit == null) {
            return List.of();
        }
        Set<String> present = toolkit.getToolNames();
        List<String> removed = new ArrayList<>();
        for (String name : DENY) {
            if (!present.contains(name)) {
                continue;
            }
            try {
                toolkit.removeTool(name);
                removed.add(name);
            } catch (RuntimeException e) {
                // 移除失败不能静默：留着就是越权工具，必须让人发现
                log.error("越权工具移除失败，工具仍然可用：tool={} error={}", name, e.getMessage());
            }
        }
        if (!removed.isEmpty()) {
            log.warn("已按策略移除越权工具：{}", removed);
        }
        return removed;
    }

    /**
     * 强制黑名单快照，供测试比对。
     *
     * @return 不可变黑名单
     */
    public static Set<String> denyList() {
        return Set.copyOf(DENY);
    }
}
