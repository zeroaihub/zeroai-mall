# Phase 4 — 事件流、会话与安全边界

> 状态：**PASS**。Phase 1 此前 BLOCKED 的「真实模型调用」已解除，根因见 §6。
> 端到端验证在 `agentscope` 引擎 + `deepseek-flash` 下完成，`curl -N` 可见完整事件序列。

## 1. 本次改动

### 新增

| 文件 | 职责 |
|---|---|
| `web/SseSink.java` | SSE 发送封装：关闭判断、关闭回调、重复发送静默丢弃，两条通道共用 |
| `agent/AgentEventMapper.java` | AgentScope 事件 → SSE 事件，**白名单映射**，无状态 |
| `agent/AgentChatHandler.java` | 请求编排：建 Agent、订阅、事件下发、取消、并发保护、错误归类 |

### 修改

| 文件 | 变更 |
|---|---|
| `web/AiChatController.java` | 支持 `engine` 双通道分派；抽出 `SseSink`；新增服务端派生 `sessionId` |
| `config/AiAgentAutoConfiguration.java` | 注册控制器时注入 `ObjectProvider<AgentChatHandler>` |
| `agent/AgentScopeAutoConfiguration.java` | 新增 `AgentChatHandler` Bean（存在即代表 Agent 通道可用） |
| `agent/MallAgentFactory.java` | workspace 按 sessionId 分目录并自动创建 |
| `application.yml`（mobile） | 新增 `engine` 配置；`model` 默认对齐 `deepseek-flash`；新增 `.env` 导入 |

### 新增测试

| 文件 | 用例数 | 覆盖 |
|---|---|---|
| `AgentEventMapperTest` | 10 | 白名单映射、推理链丢弃、工具参数丢弃、未知工具降级 |
| `AgentChatHandlerTest` | 7 | 事件顺序、错误码映射、并发拒绝、跨用户并行、断连中断 |

`zeroai-ai-starter` 测试总数 **29**，全绿。

## 2. 事件映射表

| AgentScope 事件 | 下发 | 说明 |
|---|---|---|
| `TextBlockDeltaEvent` | `message_delta` | 空 delta 丢弃 |
| `ToolCallStartEvent` | `thinking` | 仅工具名 + 固定中文文案 |
| `ToolResultEndEvent` | `tool_result` | 仅成功/失败 + 固定文案 |
| `AgentResultEvent` | `message_delta`（兜底） | 仅当全程无文本增量时补发 |
| `ThinkingBlockDeltaEvent` | **丢弃** | 推理链正文，不对外 |
| `ToolCallDeltaEvent` / `ToolCallEndEvent` | **丢弃** | 含工具原始入参 |
| 其余全部事件 | **丢弃** | 默认拒绝，SDK 升级不会自动泄漏新事件 |

工具名同样走白名单：`search_products` / `get_product_detail` / `get_stock_status` / `list_my_orders`，
未登记的工具统一对外表示为 `other`，不下发原始名称。

## 3. 会话与授权规则

- **sessionId 由服务端派生**：`u{userId}`，匿名 `anon-{uuid}`。端点**不接受**前端传入的 sessionId。
  一旦可被外部指定，就必须在每个读取点校验归属，否则会退化成横向越权入口；直接不开放是最省事的正确解。
- **sessionId 不是凭据**：工具的数据范围只由 `userId` 决定，换 sessionId 看不到别人的数据。
- **未启用会话持久化**：因此**不具备 durable execution**，进程重启后无法恢复在途任务，也不宣称能恢复。
  多轮上下文目前不跨请求保留，每轮独立。后续若接入持久化，需先确认 AgentScope 2.0.4 官方 store 能力。
- **workspace 按会话隔离**：`.agentscope/workspace/{sessionId}`，目录自动创建，会话名做字符白名单过滤防路径穿越。

## 4. 取消与超时

| 场景 | 行为 |
|---|---|
| 客户端断连 | `SseSink` 关闭回调 → `Disposable.dispose()` → `agent.interrupt()`，模型停止生成 |
| SSE 超时 | `zeroai.ai.sse-timeout-ms`（默认 120s），超时标记关闭并结束连接 |
| 同会话重复请求 | 返回 `error{code=AI_BUSY}`，不进入模型调用，避免重复计费 |
| 跨用户并发 | 互不影响，在途键为 `userId | sessionId` |

实测证据（客户端 `head` 截断主动断开）：

```
14:58:09.559 Agent 通道结束，userId=93, sessionId=u93, signal=cancel, steps=9
```

`signal=cancel` 说明订阅被真实取消，不是等模型跑完再丢弃。

## 5. 端到端验收记录

环境：`AI_ENABLED=true AI_ENGINE=agentscope`，模型 `deepseek-flash`，用户 93 / 94。

| 验收项 | 结果 | 证据 |
|---|---|---|
| `curl -N` 可见 message delta 与工具事件 | ✅ | 259 个 `message_delta` + 8 个 `thinking` + 8 个 `tool_result` + 1 个 `done` |
| 事件终止语义 | ✅ | `data:{"type":"done","data":{"truncated":false,"steps":8}}` |
| 无工具对话 | ✅ | `steps:0`，模型自述「不能下单/支付/退款」 |
| 断连停止消耗 | ✅ | `signal=cancel` |
| 用户隔离（会话） | ✅ | 93 → `sessionId=u93`，94 → `sessionId=u94` |
| 用户隔离（workspace） | ✅ | `.agentscope/workspace/` 下并存 `u93`、`u94` |
| 用户隔离（数据） | ✅ | 94 查订单只返回自己的 1 笔，模型明确「只能查询」 |
| 错误事件 | ✅ | 单测：401 → `AI_AUTH_FAILED`；其他 → `AI_STREAM_ERROR`；异常原文不下发 |
| 超时/关闭语义 | ✅ | 单测：关闭后发送静默、关闭回调只执行一次 |

模型回答质量抽查（问「推荐 5000 元以内的手机」）：模型并行检索多个关键词后，
**主动指出命中的是压测仿真商品、其余是模糊匹配误命中，并拒绝据此给出购买建议**。
这是工具返回真实数据 + 提示词约束共同作用的结果，说明链路没有伪造数据。

## 6. Phase 1 BLOCKED 解除：401 的真实原因

此前记录为「密钥被上游判为无效」，实际是 `.env` 里多写了一层前缀：

```
LLM_API_KEY=sk-sk-32ffbc...    ← 长度 38，正常应为 35
```

DeepSeek 返回 `Authentication Fails, Your api key: ****e5a6 is invalid` —— 它收到的是**非法字符串**，
不是过期密钥。修正为单个 `sk-` 后：`/models` 返回 200，`deepseek-flash` 与 `deepseek-v4-pro` 均可用，
`tool_calls` 正常产出 `{"keyword":"手机","maxPrice":5000}`。

同时补上了一个一直存在的配置缺口：**Spring Boot 不会自动读取 `.env`**，
此前只有手工 `export` 才生效。现在通过 `spring.config.import: optional:file:./.env[.properties]` 加载，
`.env` 仍不入库，CI / 生产可用真实环境变量覆盖（环境变量优先级高于导入文档）。

## 7. 已知限制与待办

1. **`server.port: 82` 在未显式指定时起在随机端口**（实测 59344）。
   已排查：应用配置、`application-dev.yml`、jar 内配置、环境变量、自定义 `EnvironmentPostProcessor`
   均无 `server.port=0` 来源。当前用 `--server.port=82` 显式兜住。
   **这是待查项**，会影响运维（按配置启动会得到随机端口）。
2. **`onErrorDropped` 噪音**：取消订阅后上游仍可能发错误信号，Reactor 打一条 ERROR 日志。
   属取消的固有副产物，不影响功能，暂未全局 suppress（避免掩盖真实背压问题）。
3. **多轮上下文不保留**：未接持久化，每轮独立。体验上表现为「记不住上一轮」。
4. **工具调用次数偏多**：一次推荐问题触发 8 次工具调用（模型并行搜索多个关键词）。
   属模型行为而非缺陷，但成本上可优化——考虑在系统提示词里约束并行检索数量。
5. **商品卡片未下发**：`product_cards` 事件类型已定义但未使用。
   `ToolResultEndEvent` 只携带执行状态、不携带工具返回值，接卡片需要另设数据通道，
   留待前端联调阶段一并设计。
6. **物流能力缺失**：`trackLogistics` 全仓零实现，Phase 3 起即记录为能力缺口，未伪造。

## 8. 回滚方式

改动集中在 `zeroai-ai-starter` 与 `zeroai-mobile-api/src/main/resources/application.yml`：

```bash
git checkout -- zeroai-ai-starter/src/main/java/com/zeroai/ai/web/AiChatController.java
rm zeroai-ai-starter/src/main/java/com/zeroai/ai/web/SseSink.java \
   zeroai-ai-starter/src/main/java/com/zeroai/ai/agent/AgentEventMapper.java \
   zeroai-ai-starter/src/main/java/com/zeroai/ai/agent/AgentChatHandler.java
```

或将 `AI_ENGINE` 置回 `spring-ai`（默认值）：`/ai/chat` 立即回到单轮问答，
Agent 相关 Bean 全部不装配，与接入前行为一致。这是最轻的回滚路径。
