# LangChain4j vs AgentScope：同一商城导购链路的框架对比

> 本文是学习底稿：两条链路实现**同一个需求**（10 个工具、同一提示词、同一 SSE 协议、同一批业务服务），
> 差异全部来自框架本身。切换方式：`.env` 里 `AI_ENGINE=agentscope | langchain4j`。
> 对照代码：`com.zeroai.ai.agent`（AgentScope 版）与 `com.zeroai.ai.engine.langchain4j`（LangChain4j 版）。
> 背景与决策见 ADR-002 / ADR-003。

## 1. 总览

| 维度 | AgentScope | LangChain4j |
|---|---|---|
| 编排入口 | `HarnessAgent`（builder 配置式） | `AiServices`（接口代理式） |
| 工具声明 | `io.agentscope.core.tool.@Tool` | `dev.langchain4j.agent.tool.@Tool` + `@P` |
| 流式模型 | `agent.streamEvents()` → 事件流 | `TokenStream` 回调链 |
| 工具循环 | 框架内建（ExceedMaxIters 事件可见） | `maxToolCallingRoundTrips`（框架静默处理） |
| 跨请求记忆 | Harness memory（可开关） | 无（每次请求独立） |
| 中断/取消 | `interrupt()` 立即断，停止计费 | 1.24 无流式中断 API，只能停止转发 |
| 状态持久化 | AgentStateStore（workspace 目录） | 无内建 |

## 2. 工具注册与描述

- AgentScope：`@Tool(name, description, readOnly, concurrencySafe)`；描述一个字符串。
- LangChain4j 1.24：`@Tool(name, value)`——**描述是 `value`（String[]），不是 `description`**；
  参数说明用 `@P(value, required)`；没有 readOnly/concurrencySafe（写权限管控要自己做）。
- 观察点：两版工具方法体一致（`langchain4j.tool` 与 `mall.tool` 平行副本），
  对比时改哪边都要同步另一边。

## 3. 流式事件时序（实测）

AgentScope（真实链路日志）：
```
事件流贯穿全程：模型增量与工具事件交错出现（边想边做）
```

LangChain4j（Spike 实测，`AiServicesToolSpikeTest`）：
```
before:search_products → executed:search_products
→ before:get_stock_status → executed:get_stock_status
→ delta × N → complete
```
**文本增量在工具循环全部结束后才开始**——AgentScope 边执行边流出。
用户体感差异：LangChain4j 通道首字更晚（工具多时更明显），这是框架调度模型决定的。

## 4. SSE 事件映射

两边共用 `AiEvent`/`SseSink`/`mall.event` 事件类，映射对照：

| LangChain4j 回调 | AgentScope 事件 | SSE 事件 |
|---|---|---|
| `beforeToolExecution` | `ToolCallStartEvent` | `thinking {tool, message}` |
| `onToolExecuted` | `ToolCallEndEvent`（映射自 AgentEventMapper） | `tool_result {tool, success, message}` |
| `onPartialResponse` | `AgentResultEvent`/增量事件 | `message_delta {delta}` |
| `onCompleteResponse` | 完成 | `done {steps, truncated}` |
| `onError` | 错误 | `error {code, message}` |
| 工具内 `GuideUiEvents` 回调 | 同左 | `product_cards` / `cart_added` / `cart_select` |

LangChain4j 独有的中间回调（本次未用，可作后续观察）：
`onPartialToolCall`（工具参数流式）、`onPartialThinking`（思维链）、`onIntermediateResponse`。

## 5. 参数绑定

- AgentScope：集合类型（`List<Long>`）绑定会失败，工具方法体不执行（已踩坑，见工作日志）。
- LangChain4j：Gson 绑定，本次统一用「字符串 ID + 解析器」与 AgentScope 版对齐，
  顺带规避两边差异；`Long/Integer` 包装类型与 `required=false` 可选参数实测正常。

## 6. 步数与止损

- AgentScope：`maxSteps` 按事件步数，超限发 `ExceedMaxItersEvent`，可映射 truncated；
- LangChain4j：`maxToolCallingRoundTrips` 按模型往返次数（一轮可含多个并行工具调用），
  超限行为框架内静默收敛，**没有可观测的截断事件**（`done.truncated` 恒为 false）；
- 共同点：`search_products` 的服务端计数止损（3 次/轮）在两边都生效，不依赖框架。

## 7. 安全边界的一致性

两条通道共用同一批业务服务与同一套工具语义，安全设计不因框架而变：
userId 构造期注入（模型不可指定他人）、匿名不注册用户级工具、
地址/手机号脱敏、提议只弹层不建订单、加购可逆而订单需用户确认。

## 8. 学习实验建议

1. 观察两边日志：`grep "工具调用开始\|工具执行完成" logs` 对比同问题的步数与耗时；
2. 切引擎跑同一句话（如「把小米手机下单」），对比 SSE 事件流的时序差；
3. 故意写错引擎名，观察回落与告警日志；
4. 客户端中途断连（点停止），对比日志里的取消行为差异（§1 取消语义）。
