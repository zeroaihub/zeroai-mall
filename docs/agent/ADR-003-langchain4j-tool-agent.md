# ADR-003 — LangChain4j 升级为带工具的 Agent 通道（AiServices 编排，与 AgentScope 平行隔离）

- **状态**：Accepted
- **日期**：2026-11-24
- **决策范围**：LangChain4j 的能力边界（单轮问答 → 带工具 Agent）、与 AgentScope 通道的隔离边界、编排方式
- **关联**：建立在 ADR-002（LangChain4j 以端口隔离方式接入）之上

## 1. 背景

ADR-002 只把 LangChain4j 接到「单轮无工具问答」，与 `spring-ai` 对等。
用户明确两点新要求：

1. LangChain4j **要具备工具能力**（商品搜索、加购、下单弹层等完整导购链路）；
2. **不和既有代码耦合**——目的是学习 LangChain4j，后续要对比它与 AgentScope 的差异。

## 2. 隔离边界（本次最关键的决策）

「不耦合」不等于「什么都不共享」，共享分两类：

**共享（业务层，重写反而有害）：**

| 共享项 | 理由 |
|---|---|
| domain service（商品/购物车/订单/地址） | 商城业务入口，与框架无关 |
| `ActionProposalService` | 提议幂等、金额快照、SUPERSEDED 唯一性；重写等于业务逻辑分叉 |
| `SseSink` / `AiEvent` / `AiEventType` | SSE 出口协议，前端契约固定 |
| `GuideUiEvents` + `mall.event` 事件类 | 纯 JDK 接口/POJO，复用才能发 `product_cards`，前端零改动 |
| `mall-agent-system.st` 提示词 | 对比学习的前提：同 prompt、同工具名，差异才纯粹来自框架 |

**严格隔离（框架编排层）：**

```
com.zeroai.ai.engine.langchain4j
├── ChatStreamEngine 等                    ← ADR-002 的单轮端口（保留为降级兜底）
├── tool/                                  ← 10 个工具的 LangChain4j @Tool 平行版本
│   ├── JsonText / IdListParser            ← mall.tool 同名工具类的副本
│   └── SearchProductsTool ... ProposeOrderTool
├── agent/Lc4jAgentHandler                 ← AiServices + TokenStream 编排（对标 AgentChatHandler）
└── LangChain4jEngineAutoConfiguration     ← 装配（模型 + 单轮引擎 + Agent 编排器）
```

约束（有测试锁定，见 §6）：`engine.langchain4j` 包**不得 import `io.agentscope.*` 与
`com.zeroai.ai.mall.tool.*`**；AgentScope 侧也不感知它的存在。

被否的替代方案：把 10 个工具抽成框架无关端口供两套编排共用（"正路"但正是用户要求避免的耦合）；
在检索/工具层堆参数（ADR-002 已否）。

## 3. 编排方式：AiServices（用户确认）

- 服务接口 `Assistant { TokenStream chat(String message); }`，
  `AiServices.builder().streamingChatModel(model).systemMessage(prompt).tools(...).maxToolCallingRoundTrips(maxSteps).build()`；
- 框架自动执行工具调用循环；每次请求构建一个助手实例（工具持有 userId，与 AgentScope 版「每请求一 Agent」一致）；
- 步数上限用 `maxToolCallingRoundTrips`（按模型往返计，与 AgentScope 的事件步数语义不完全等价）；
- 不配 `chatMemory`：无跨请求会话记忆（AgentScope 有 Harness 记忆，这是对比点之一）。

## 4. 引擎语义升级

```
spring-ai     纯单轮，无工具（兜底）
langchain4j   带工具的 Agent（本 ADR）→ 装配失败降级单轮
agentscope    带工具的 Agent（生产链路，未改动）
```

`AiChatController` 按 engine 分发：`agentscope` → AgentChatHandler；
`langchain4j` → Lc4jAgentHandler；其余/降级 → ChatStreamEngine 单轮。
SSE 事件协议三通道一致，前端零改动。

## 5. 代价与风险

| 项 | 等级 | 说明与缓解 |
|---|---|---|
| TokenStream 无取消 API | 中 | 1.24 没有流式中断入口，断连只能停止事件转发，上游继续跑完（AgentScope 可 interrupt 立即断）。已在 `Lc4jAgentHandler` 类注释记录 |
| 工具逻辑双份漂移 | 中 | lc4j.tool 与 mall.tool 是平行副本，方法体必须同步修改；类注释互相标注来源 |
| `AiServices` 反射构建成本 | 低 | 每请求构建一个助手（约毫秒级）；AgentScope 版同样每请求建 Agent |
| 事件时序差异 | 低 | Spike 实测：`before → executed → …工具全部完成… → delta* → complete`，文本增量在工具循环结束后才开始；AgentScope 是边执行边流出。已按实测时序实现映射 |
| 1.24 `@Tool` 注解属性变化 | 已消除 | 描述用 `value`（String[]）而非 `description`；参数描述用 `@P` |

## 6. 验证方式与实测记录

- **Spike**（`AiServicesToolSpikeTest`，环境变量 `LC4J_SPIKE=true` 手动触发）：
  真实模型 + 2 个假工具，确认自动执行工具并续流，事件时序如上；
- **架构守护测试** `LangChain4jIsolationGuardTest`：扫描源码断言 langchain4j 包无禁止 import；
- **装配测试**：`LangChain4jEngineAutoConfigurationTest` 开关只影响自己、缺 key 快速失败；
- **全量**：`mvn -pl zeroai-ai-starter test` 269 项全绿（Spike 需手动触发故 skip 1）；
- **端到端**：`--zeroai.ai.engine=langchain4j` 启动实测（进行中，见 §7）。

## 7. 实测记录（2026-11-24，待补充）

- 工具链路已通：`search_products` 执行成功（45ms）、`before/executed` 回调映射 SSE 正常、
  检索降级时模型正确转述「暂时查不到」（`SEARCH_UNAVAILABLE` 分支）；
- 中间件验证中断：Docker Desktop 未运行导致 MySQL/ES 不可用，恢复后补「搜索出卡片 / 对话加购 / 下单弹层」三场景实测。

## 8. 回滚方式

`AI_ENGINE=agentscope` 即回到原生产链路；移除 LangChain4j Agent：
删 `langchain4j.tool`、`langchain4j.agent` 包 + 装配类中 `lc4jAgentHandler` bean，
单轮引擎（ADR-002）可保留。

## 9. 重新评估条件

- LangChain4j 提供流式中断 API（对齐取消语义）；
- 需要跨请求会话记忆（配 `chatMemoryProvider`，注意与 AgentScope 记忆的语义差异）；
- 对比学习结束、确定生产编排框架后，另一套通道的去留。
