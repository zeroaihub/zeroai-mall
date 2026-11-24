# ADR-002 — 以端口隔离方式接入 LangChain4j，与 Spring AI 并列可切换

- **状态**：Accepted（装配、单测、三种引擎真实模型调用均已验证，见 §7）
- **日期**：2026-11-24
- **决策范围**：是否引入 LangChain4j、引入哪个版本、以什么方式与 Spring AI 共存而不互相污染

## 1. 背景

`zeroai.ai.engine` 原本只有两条通道：

- `spring-ai`：单轮无工具问答，实现直接写在 `AiChatController`（依赖 `ChatClient`）与 `AiAgentAutoConfiguration`（构建 ChatModel / ChatClient）里；
- `agentscope`：带只读商城工具的 Agent，独立链路。

需求是「同时接入 LangChain4j，用同一个开关切换 Spring AI 与 LangChain4j，两套代码不能揉在一起」。这带来三个必须先回答的问题：

1. 版本取哪个，是否与 Boot 4.1.2 / Java 21 兼容，会不会引入新的依赖冲突？
2. 切换点放在哪一层，才能既复用现有配置与 SSE 协议，又不让两套 SDK 的类型互相渗透？
3. LangChain4j 的流式能力与 Spring AI 有哪些客观差异，是否影响现有「停止生成 / 断连收尾」行为？

## 2. 版本选择

| 项 | 取值 | 依据 |
|---|---|---|
| LangChain4j | **1.24.0** | `repo1.maven.org` 的 `dev.langchain4j:langchain4j-open-ai` `maven-metadata.xml` 中 `<release>` 为 `1.24.0`（2026-11-24 核对），非 beta/rc |
| 依赖坐标 | `langchain4j-open-ai` | 只需 OpenAI 兼容协议；不引聚合包 `langchain4j`，避免带进大量未使用的集成 |
| HTTP 实现 | `langchain4j-http-client-jdk`（runtime 传递） | JDK HttpClient，**不引入 okhttp**，与 `openai-java 4.80.0` 的 okhttp 4.12.0 无冲突 |
| Spring 集成 | **不用** `langchain4j-spring-boot-starter` | 该 starter 会接管自己的配置前缀与 Bean 命名，与 `zeroai.ai.*` 既有体系冲突；手工装配一个模型 Bean 即可 |

> 与 ADR-001 同一条纪律：**版本写死，不用 LATEST/RELEASE**。升级前先跑本 ADR §5 的检查。

## 3. 决策

引入 LangChain4j 1.24.0，**只在 `zeroai.ai.engine=langchain4j` 时装配**，并以端口隔离：

```
com.zeroai.ai.engine                  ← 端口，只有 JDK 类型
├── ChatStreamEngine                  name() + stream(question, listener) → handle
├── ChatStreamListener                onDelta / onError / onComplete
├── ChatStreamHandle                  cancel()（幂等）
├── ChatEngineNames / ChatEngineSelector
├── com.zeroai.ai.engine.springai     ← 只能 import org.springframework.ai.*
└── com.zeroai.ai.engine.langchain4j  ← 只能 import dev.langchain4j.*
```

规则：

1. `AiChatController` 与 `AiAgentAutoConfiguration` **不出现任何模型 SDK 类型**，只依赖端口；
2. 两个适配器包互不引用，公共逻辑只能上提到端口包；
3. System Prompt、baseUrl、apiKey、model、temperature、maxTokens 全部复用 `AiAgentProperties`，
   保证「切引擎只换框架，不换环境」——否则体验差异无法归因到框架本身；
4. 引擎名写错时回落 `spring-ai` 并打 WARN，不让对话入口不可用（与 agentscope 装配失败的处理一致）。

**范围界定**：LangChain4j 实现的是「单轮无工具问答」这一通道的等价实现，与 `spring-ai` 同能力。
带工具的 Agent 通道仍由 AgentScope 承担。若要让 LangChain4j 承接工具编排（AiServices + Tool 规范），
属于另一项决策，涉及重新实现 10 个工具、事件映射与权限边界，不在本次范围。

## 4. 备选方案与被否原因

| 方案 | 描述 | 否决原因 |
|---|---|---|
| A. 在 `AiChatController` 里加 if/else 直接调两套 SDK | 改动最小 | 控制器同时 import 两套 SDK，正是需求明确禁止的「融合」；后续新增引擎会继续堆分支 |
| B. 拆成独立 Maven 模块（engine-api / engine-springai / engine-langchain4j） | 依赖级隔离最彻底 | 为一次接入新增 3 个模块，父 POM、构建顺序、发布坐标都要动；端口已把耦合点收敛到一处，后续要拆模块不需要改调用方 |
| C. 用 LangChain4j 承担完整 Agent（工具 + 事件） | 能力对等 | 需要重写工具层、事件映射、步数与权限边界，等于第二套 Agent 运行时；维护成本远超收益 |
| **D. 端口 + 包级隔离（采纳）** | 一个端口、两个适配器包、按配置装配 | 隔离边界清晰，改动集中，回滚只需把 `engine` 改回 `spring-ai` |

## 5. 代价与风险

| 风险 | 等级 | 说明与缓解 |
|---|---|---|
| 取消语义不对等 | 中 | Spring AI 基于 Reactor，`dispose` 立即断开；LangChain4j 1.24 无公开 abort，`cancel()` 只停止转发，上游 HTTP 流会继续到结束。列表页「停止」行为一致，但极端情况下会多消耗少量 token。已在 `ChatStreamHandle` 类注释与本 ADR 记录；若成本敏感，可改用 `Flow.Publisher` 版本再评估 |
| `langchain4j-reactive-streaming:1.24.0-beta34` | 中 | 该 beta 是 `langchain4j-open-ai` 的官方传递依赖（非本项目指定）。已锁在 1.24.0 一套坐标里；升级时重点回归流式行为 |
| Jackson 2 并存 | 低 | LangChain4j 带 Jackson 2.21.x，Boot 4 默认 Jackson 3（`tools.jackson`），包名不同可共存；`dependency:tree -Dverbose` 未报冲突 |
| 两套引擎行为漂移 | 中 | 共用同一 Prompt 与配置；对比时以 `engine=spring-ai` 为基线，LangChain4j 只替换传输层 |
| base-url 拼接口径不同 | 已消除 | Spring AI 会把无路径的 base-url 规范成 `<base>/v1/chat/completions`，LangChain4j 只拼 `<base>/chat/completions`。适配器用 `resolveBaseUrl` 补齐 `/v1`（已带路径的原样保留），并有单测覆盖 |
| 请求/响应日志泄漏 | 已消除 | LangChain4j 默认 `logRequests/logResponses=true` 会打印完整 Prompt 与用户原话（可能含手机号/地址），适配器显式关闭 |

## 6. 验证方式

- 单测：`ChatEngineSelectorTest`（选择/回落/空集合）、`LangChain4jChatStreamEngineTest`（消息拼装、增量顺序、兜底文本、取消、异常）、`LangChain4jEngineAutoConfigurationTest`（开关只影响自己、缺 key 快速失败、base-url 规范化）；
- 依赖：`mvn -pl zeroai-ai-starter dependency:tree` + `-Dverbose` 确认无 okhttp / Jackson 冲突；
- 真机：同一实例分别以三种 `engine` 启动，`curl -N /ai/chat` 观察 `thinking → message_delta … → done` 事件序列；
- 回归：`mvn -pl zeroai-ai-starter test` 全绿，`agentscope` 通道的 Agent 日志仍为 `channel=agent`。

## 7. 实测记录（2026-11-24）

同一台机器、同一份 `.env`（base-url 指向本机反代），只改 `--zeroai.ai.engine`：

| engine | 日志 resolved | 首个 message_delta | 结束 |
|---|---|---|---|
| `langchain4j` | `resolved=langchain4j channel=single-turn` | 4.89s | `event:done`，14 条增量 |
| `spring-ai` | `resolved=spring-ai channel=single-turn` | 6.64s | `event:done` |
| `agentscope`（默认） | `channel=agent` | — | `Agent 通道结束 signal=onComplete steps=0` |

首字差异属于模型侧波动，不作为框架性能结论；此处只证明三条链路都能通、互不影响。

## 8. 回滚方式

1. `AI_ENGINE=spring-ai`（或删掉该行用默认值）即回到原链路，无需改代码；
2. 若需彻底移除：删 `pom.xml` 中 `langchain4j-open-ai` 依赖与 `langchain4j.version` 属性，
   删 `com.zeroai.ai.engine.langchain4j` 包与 `AutoConfiguration.imports` 中对应一行；
3. 端口与 Spring AI 适配器可保留：它们不依赖 LangChain4j，删除后仍等价于原实现。

## 9. 重新评估条件

- 需要 LangChain4j 承接工具编排（AiServices / MCP 工具 / 结构化输出）；
- LangChain4j 提供可中断的流式 API，取消语义可与 Spring AI 对齐；
- 需要把引擎做成独立 Maven 模块（引入第二个使用方、或要求按引擎裁剪产物）；
- 升级到 LangChain4j 新大版本时，先重跑 §5 的依赖与 §6 的单测。
