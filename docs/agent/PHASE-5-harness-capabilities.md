# Phase 5 — Harness 能力实验：Workspace / Skills / Memory / Middleware / Plan Mode

- **日期**：2026-10-26
- **前置**：Phase 0–4 全部通过（`/ai/chat` 已走 AgentScope 通道，事件协议稳定）
- **本阶段性质**：**能力学习与边界验证**，不是功能交付。所有能力默认关闭。
- **结论**：能力均可用，但**四项默认关闭**；同时发现并修复了 3 个安全问题、1 个运维问题。

---

## 1. 结论摘要

| 能力 | 可用性 | 默认值 | 是否建议生产 |
|---|---|---|---|
| Workspace 隔离 | ✅ | 始终启用 | 是 |
| Skills（只读技能） | ✅ jar 环境可用 | **关闭** | 试点后评估 |
| Memory（长期偏好） | ✅ 业务侧实现 | **关闭** | 是（但只存偏好） |
| Middleware（可观测） | ✅ | **开启** | 是，几乎无成本 |
| Plan Mode | ⚠️ 可用但非安全边界 | 关闭 | 否 |
| 子 Agent | ❌ 已强制关闭 | 关闭 | 否 |

**最重要的一句话**：这些能力里只有「可观测中间件」值得默认开；其余都会扩大 Agent 的作用面，
而商城导购的最小可用集只是「4 个只读工具 + 1 段 system prompt」。

---

## 2. 开关总览

配置前缀 `zeroai.ai.harness`，全部可通过环境变量覆盖：

| 配置项 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `skills-enabled` | `AI_SKILLS_ENABLED` | `false` | 是否加载技能 |
| `allowed-skills` | — | 两个商城技能 | 白名单，不在名单内的技能不生效 |
| `skills-location` | — | `skills` | classpath 目录，打包后从 jar 内读 |
| `memory-enabled` | `AI_MEMORY_ENABLED` | `false` | 长期偏好记忆 |
| `memory-dir` | — | `.agentscope/memory` | 按用户分目录 |
| `observability-enabled` | `AI_OBSERVABILITY_ENABLED` | `true` | 耗时/token/工具状态 |
| `plan-mode-enabled` | `AI_PLAN_MODE_ENABLED` | `false` | 仅只读研究任务 |
| `state-dir` | — | `.agentscope/state` | 会话状态，替代 SDK 默认的 `~/.agentscope/state` |

启动时会打印一行配置快照，便于运维核对：

```
Agent 通道配置 engine=agentscope model=deepseek-flash skillsEnabled=false
allowedSkills=[mall-shopping-guide, order-query-guide] memoryEnabled=false
observabilityEnabled=true planModeEnabled=false
```

---

## 3. 逐项实验

### 3.1 Workspace

**设计**：每个会话一个目录 `.agentscope/workspace/<sessionId>`，会话名做 `[^A-Za-z0-9_-]` 白名单过滤防路径穿越。

**验证**：
- `PlanModeAndWorkspaceTest.workspacesAreIsolatedPerUser`：两个用户目录并存且互不影响
- `AgentToolSurfaceTest`：工具集里不存在 `read_file` / `write_file` / `list_dir` / `shell`

**结论**：Agent **没有**任何文件读写工具，「读写仓库根目录」这个风险在工具层就不存在，
不依赖 workspace 目录权限。workspace 只是 SDK 的隐含状态载体。

### 3.2 Skills

**设计**：两个技能放在 `zeroai-ai-starter/src/main/resources/skills/`：

| 技能 | 内容 | 授予的权限 |
|---|---|---|
| `mall-shopping-guide` | 何时搜商品、参数怎么填、结果怎么讲、禁止事项 | **无**，只描述怎么用已有工具 |
| `order-query-guide` | 订单查询前提、何时调、怎么讲、禁止事项 | **无** |

技能正文明确写了「本文件只描述怎么用已有工具，不新增任何权限」。

**验证**：
- `SkillRepositoryProbeTest`：classpath 能加载两个技能，frontmatter 解析正常
- `MallAgentFactoryTest.shouldLoadWhitelistedSkillsWhenEnabled`：开启后两个技能进仓库
- `MallAgentFactoryTest.shouldIgnoreSkillsPlantedInWorkspace`：workspace 植入的 `evil-skill` 不加载
- 真机（jar 环境）：`ClasspathSkillRepository: is in Jar environment: true` + `技能已装配 names=[...]`

**⭐ 发现的安全问题（已修复）**：SDK 的 Layer-3 技能仓库会**无条件**挂载 `<workspace>/skills`，
`disableDefaultWorkspaceSkills()` 只影响 Layer-4。也就是说，只要有人能往该目录写一个 `SKILL.md`，
就能改写模型行为，且完全绕过工具白名单。

修复：`workspaceOf()` 在创建目录后检查 `<workspace>/skills` 是否存在，存在则**隔离到新的空目录**并记 ERROR，
不删除原目录以保留取证线索。

**⭐ 发现的第二个问题（已修复）**：即使关掉 filesystem/shell/web/memory/subagents，
`skill_manage` 与 `propose_skill` 仍会随技能框架注册进工具集——前者允许模型**自己创建和修改技能文件**。

修复：新增 `ToolSurfacePolicy`，构建后按黑名单强制剔除，实测工具数 **14 → 6**：

```
search_products, get_product_detail, get_stock_status, list_my_orders,
load_skill_through_path, wait_async_results
```

### 3.3 Memory

**设计**：明确区分两种记忆，且**都不交给 SDK 自动写**。

| 类型 | 载体 | 生命周期 | 内容 |
|---|---|---|---|
| 会话记忆 | SDK `AgentStateStore`（`stateDir`） | 一次会话 | 对话上下文，用于「接着上一句说」 |
| 长期记忆 | 自研 `UserPreferenceMemory` | 跨会话 | 只存用户显式确认的偏好 |

**为什么不用 SDK 的 `memory_save`**：该工具的参数由模型自由生成，
无法满足「用户明确允许才写入」这一前提。写入决策必须留在业务侧，模型只能建议。

**安全设计**（`MemorySafetyGuard`，13 个用例）：

| 规则 | 拦什么 |
|---|---|
| 手机号 | `1[3-9]\d{9}` |
| 身份证 | 18 位，末位可 X |
| 长数字串 | 12–19 位连续数字（订单号 16 位 / 银行卡） |
| 密钥形态 | `sk-…`、`Bearer …`、`ghp_…` |
| 地址 | 省市区县 或 XX路/街/小区/号楼 |
| 敏感词 | 收货地址、身份证、验证码、密码、CVV、订单号… |
| 长度/空值 | >200 字或空白 |

**策略是「命中即整条拒绝」，不做脱敏保留**——脱敏残片仍可能被模型拼回去，且会给人「已经安全」的错觉。

**验证**：`MemorySafetyGuardTest` 15 个用例，覆盖每条规则的拒绝、按用户隔离、去重、条目上限、删除、非法 userId、多行注入。

**实测**：开启后 `.agentscope/memory` 目录创建成功，但**没有任何文件写入**——因为本阶段没有接入
「用户确认写入」的入口。这是刻意的：没有确认入口就不该有写入。

### 3.4 Middleware（可观测）

**设计**：`AiObservabilityMiddleware implements MiddlewareBase`，洋葱模型外层（`order=-100`）。

| 拦截点 | 记录内容 | 明确不记录 |
|---|---|---|
| `onModelCall` | 耗时、消息条数、工具数、in/out/cached/total token、结束信号 | Prompt 正文 |
| `onActing` | 工具名、耗时、`ToolResultState` | 工具入参、返回值 |

**验证**：`AiObservabilityMiddlewareTest` 8 个用例，含「日志绝不含 Prompt 正文与工具入参」
「上下文缺失降级 anonymous」「工具名日志注入被清洗」「下游异常原样抛出」。

**真机输出**（B 组）：

```
ai.model.call session=u93 ms=1366 msgCount=6 toolCount=6 in=4233 out=161 cached=3968 total=4394 signal=onComplete
ai.tool.call  session=u93 tool=search_products ms=215 state=SUCCESS
```

**成本**：日志写入是旁路，不影响返回的事件流。但**关掉它就没有 token 数据**——A 组因此拿不到用量（见第 4 节）。

### 3.5 Plan Mode

**设计**：独立开关，计划文件写 `<planDir>/<sessionId>`，并 `allowShellInPlanMode(false)`。

**⭐ 发现的设计事实**：`enablePlanMode(false)` 只控制**工具注册**，
`HarnessAgent.enterPlanMode()` 仍然可以被强行调用（实测 `isPlanModeActive` 变 true）。

**结论**：**Plan Mode 是能力开关，不是权限边界**。若将来要用，必须在业务侧自己控制谁能进，
不能指望这个 flag 拦住越权。另外 `enablePlanMode(true)` 的语义是「以规划态启动」，不是「允许手动进入」。

### 3.6 子 Agent

已用 `disableSubagents()` + `disableDynamicSubagents()` 强制关闭。
原因不只是「用不上」：`SubagentsMiddleware` 注册子 Agent 工具时会**顺带把技能管理工具带进来**（见 3.2）。

---

## 4. 四组对比实测

同一问题「帮我推荐5000元以内的手机」，每组重启服务并清空 `.agentscope` 后单次采样：

| 组 | 技能 | 观测 | 记忆 | 端到端 | 模型调用 | input | output | total | 工具调用 | steps |
|---|---|---|---|---|---|---|---|---|---|---|
| A | — | — | — | 7394ms | 未采集 | — | — | — | 4 | 4 |
| B | — | ✅ | — | 7969ms | 3 | 14499 | 575 | 15074 | 9 | 6 |
| C | ✅ | ✅ | — | 8977ms | 4 | 20061 | 831 | 20892 | 6 | 6 |
| D | ✅ | ✅ | ✅ | 7707ms | 4 | 20953 | 754 | 21707 | 5 | 5 |

**必须说清楚的局限**：每组只跑了 1 次，而 `steps` 在 4/5/6 之间随机波动
（B、C 都撞到了 `max-iters=6` 上限）。因此这张表**不能**用来断言「开技能会增加 X% token」——
它只能证明：能力开关不影响链路可用性，且 token 量级在 15k–22k 之间。

要得到可信的因果结论，至少需要每组 20 次采样并固定 prompt 与商品数据。

> **后续补测（2026-10-26）**：已按上面的要求做了固定评测集 + 配对采样，
> 见 `docs/agent/BASELINE-METRICS.md` §4。上表的单次采样数字不要再引用，以新文档为准。

**能站得住的观察**：

1. **首轮上下文约 4.2k input token**（C/D 组 `msgCount=2` 时 `in≈4200`），系统提示 + 技能 + 工具 schema 的固定开销就在这个量级。
2. **缓存命中率很高**：`cached` 占 `in` 的 80–95%，说明 DeepSeek 前缀缓存对稳定 prompt 有效，实际计费远低于名义 input。
3. **工具数从 14 降到 6 后，input token 明显下降**（旧代码同问题 25k+ → 新代码 20k），这是黑名单带来的间接收益。
4. **工具调用是主要耗时来源**：单次 `search_products` 约 215ms，6 次并行搜索就占了端到端的 1/3。

---

## 5. 发现的问题与处理

| # | 问题 | 严重度 | 处理 |
|---|---|---|---|
| 1 | `<workspace>/skills` 被无条件当技能仓库挂载，可被植入 SKILL.md 改写模型行为 | **高** | 检测即隔离目录 + 记 ERROR |
| 2 | `skill_manage` / `propose_skill` 自动注册，模型可自建技能 | **高** | `ToolSurfacePolicy` 黑名单剔除 |
| 3 | 会话状态默认写 `~/.agentscope/state`，污染 home 且容器不可共享 | 中 | 显式 `stateStore` 到 `.agentscope/state` |
| 4 | `enablePlanMode(false)` 不阻止 `enterPlanMode()` | 中 | 记录为「非安全边界」，不依赖它做权限 |
| 5 | 会话历史在同一 userId 下持续累积（msgCount 25→35），长会话 input 膨胀 | 中 | **已补测**，压缩生效但集中在触发轮付费，见 `BASELINE-METRICS.md` §5 |

---

## 6. 未解决 / 不建议现在上生产

> 2026-10-26 补测：本节第 1、3 项已用固定脚本量化，结论写进 `docs/agent/BASELINE-METRICS.md`，
> 此处保留原始判断并标注实测结果，不再只写「未验证」。

1. **长会话上下文膨胀** → **已验证**：20 轮连续对话中 msgCount 从 7 涨到 46，第 12 轮压缩掉到 24，
   之后继续上涨到 50。压缩确实生效且上下文没断（压缩后仍记得前几轮买过什么），
   代价集中在触发轮：该轮 input 34009、整轮 20.9s。20 轮只触发 1 次，可接受。
2. **`product_cards` 仍未做**（Phase 4 遗留）：`ToolResultEndEvent` 不带工具返回值，卡片需要另设数据通道。
3. **技能对模型行为的实际影响** → **已量化**：同一评测集 24 条配对样本，开启技能后
   input token ×1.148、整轮耗时 ×1.186、首字耗时 ×1.404、步数 +1，且 16/24 条样本会调用
   `load_skill_through_path`（关闭时 0 次）；工具选择与拒答质量**没有提升**（已到天花板）。
   → 维持「默认关闭」。
4. **`skills/_drafts` 与技能缓存目录**：`.skills-cache` 已在 workspace 内生成，未纳入清理策略。

---

## 7. 回滚方式

| 想回到 | 操作 |
|---|---|
| Phase 2/3 状态（无技能/无观测/无记忆） | `AI_SKILLS_ENABLED=false AI_OBSERVABILITY_ENABLED=false AI_MEMORY_ENABLED=false` |
| Phase 4 状态（Agent 可用但无 Phase 5 能力） | 同上（默认值即此状态，无需任何操作） |
| 完全关闭 AI | `AI_ENABLED=false` |
| 移除新增代码 | 删除 `ai/harness/*`、`ai/memory/*`、`resources/skills/*`，回退 `MallAgentFactory` 与 `AgentScopeAutoConfiguration` |

所有能力默认关闭，因此**不配置任何环境变量就是 Phase 4 的行为**，回滚不需要改代码。

---

## 8. 变更文件

**新增（8 个主代码 + 4 个测试）**

```
ai/harness/HarnessProperties.java          能力开关
ai/harness/AiObservabilityMiddleware.java  可观测中间件
ai/harness/ToolSurfacePolicy.java          工具黑名单
ai/memory/MemorySafetyGuard.java           记忆写入守卫
ai/memory/UserPreferenceMemory.java        按用户隔离的偏好存储
resources/skills/mall-shopping-guide/SKILL.md
resources/skills/order-query-guide/SKILL.md
（测试）AiObservabilityMiddlewareTest / MemorySafetyGuardTest / AgentToolSurfaceTest / PlanModeAndWorkspaceTest
```

**修改**

```
ai/agent/MallAgentFactory.java              能力装配 + workspace 隔离 + stateStore
ai/agent/AgentScopeAutoConfiguration.java   注册 HarnessProperties / UserPreferenceMemory + 配置日志
ai/config/... （无）
zeroai-mobile-api/src/main/resources/application.yml   harness 配置段
zeroai-ai-starter/src/test/.../MallAgentFactoryTest.java  适配新构造 + 技能断言
```

**测试**：`ai-starter` 从 29 个增至 **64 个**，全绿；全量 19 模块 `BUILD SUCCESS`。
