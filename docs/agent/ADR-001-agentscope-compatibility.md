# ADR-001 — AgentScope Java Harness 与 Boot 4.1.2 兼容性结论

- **状态**：Accepted（依赖装配 / 事件流 / 真实模型调用均已验证，见 §9 更新）
- **日期**：2026-10-26
- **决策范围**：是否在本仓库引入 `io.agentscope:agentscope-harness`，以及引入哪个版本、用哪个模型 Provider

## 1. 背景

商城当前技术栈为 Java 21 / Spring Boot 4.1.2 / Spring AI 2.0.2。
Spring AI 侧只有 `ChatClient` 单轮调用（`zeroai-ai-starter`），无工具调用、无会话、无事件流。
方案要求引入 AgentScope Java Harness 作为对照实现，但**不得**因为引入新框架而升级或污染现有栈。

引入前必须回答三个问题：

1. AgentScope 依赖能否在当前多模块工程解析成功，且不强行改写已解析版本？
2. 在 Java 21 上，Harness 能否被装配和调用（不是只看编译通过）？
3. 两个框架共存会不会把旧的 Spring AI 路径搞坏？

## 2. 版本选择

| 项 | 取值 | 依据 |
|---|---|---|
| AgentScope Java | **2.0.4** | Maven Central `maven-metadata.xml` 中 `2.1.0-BETA1` 是最新但为 beta；2.0.4 是最新稳定版 |
| 依赖坐标 | `agentscope-harness` + `agentscope-extensions-model-openai` | Harness 会传递引入 `agentscope-core`；模型 Provider 只引一个 |
| 是否用 `agentscope-spring-boot-starter` | **暂不** | 该 starter 会接管 `Model` Bean 与 `agentscope.*` 配置，与本项目既有 `zeroai.ai.*` 体系冲突；先手工装配，Phase 2 再决定 |

> 注意：Maven Central 的 `<latest>` / `<release>` 字段都指向 `2.1.0-BETA1`。
> 如果按 `<release>` 取版本，会误装 beta 版。**版本必须写死，不能用 LATEST/RELEASE。**

## 3. 决策

采用 AgentScope **2.0.4**，以 `agentscope-harness` + `agentscope-extensions-model-openai` 组合接入，
OpenAI 兼容端点由 `OpenAIChatModel.builder().baseUrl(...)` 显式指定，不依赖厂商环境变量默认值。

不做全局 `<exclusions>`，不做版本强制覆盖。

## 4. 证据

### 4.1 依赖解析证据

完整依赖树见 `docs/agent/evidence/agentscope-dependency-tree.txt`
（生成命令 `mvn -Pspike -pl zeroai-agent-spike dependency:tree`）。

关键对照（左：AgentScope 引入后解析到的版本；右：现有 `zeroai-mobile-api -am` 已解析的版本）：

| 依赖 | AgentScope 需要的 | 项目现有 | 结论 |
|---|---|---|---|
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.7 | 2.21.7 | **完全一致** |
| `com.fasterxml.jackson.core:jackson-core` | 2.21.7 | 2.21.7 | 完全一致 |
| `io.projectreactor:reactor-core` | 3.8.8 | 3.8.8 | 完全一致 |
| `org.springframework:spring-core` | 由 Boot 管理 | 7.0.10 | 由 BOM 统一 |
| `org.slf4j:slf4j-api` | 2.0.20 | 2.0.20 | 完全一致 |
| `ch.qos.logback:logback-classic` | 1.5.38 | 1.5.38 | 完全一致 |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | 4.12.0 | 完全一致 |

**Jackson 2 与 Jackson 3 的关系（重点）**：

Spring Boot 4 默认使用 Jackson 3（`tools.jackson.core:jackson-databind:3.1.7`），
而 AgentScope 与 Spring AI 的 OpenAI SDK 仍使用 Jackson 2（`com.fasterxml.jackson.core:*:2.21.7`）。

两者包名不同（`tools.jackson` vs `com.fasterxml.jackson`），可以共存，
且本项目**在引入 AgentScope 之前就已经同时存在这两套**——所以这是一条既有事实，不是本次引入带来的新风险。

### 4.1.1 对上面结论的修正（Phase 2 实测推翻了「零冲突」）

上表只覆盖了 spike 模块（仅 `agentscope-harness` + `spring-boot-starter-test`）的依赖解析。
把 AgentScope 真正接进 `zeroai-ai-starter`（该模块同时依赖 **Spring AI 2.0.2**）之后，
暴露出两个 spike 阶段看不到的冲突。**这一节优先于 4.1 的结论。**

| 冲突 | 双方要求 | 症状 | 处理 |
|---|---|---|---|
| `com.github.victools:jsonschema-generator` | Spring AI 2.0.2 → **5.0.0**（compile）<br>AgentScope 2.0.4 → **4.38.0**（compile） | 构建 Agent 时抛 `NoSuchMethodError: SchemaGenerator.generateSchema(Type, Type[])`；5.x 移除了该重载 | 钉 **4.38.0**。AgentScope 的工具 schema 生成是活跃路径必须可用；Spring AI 仅结构化输出使用 victools，本通道不用 |
| `com.squareup.okhttp3:okhttp` / `okhttp-jvm` | openai-java 4.80.0 → `okhttp:4.12.0`<br>AgentScope → `okhttp-jvm:5.3.2` | 两者包名均为 `okhttp3.*`，同 classpath 存在二进制歧义 | 从两个 agentscope 依赖上排除 `okhttp-jvm`，统一保留 4.12.0（AgentScope 默认走 `JdkHttpTransport`，不依赖 OkHttp） |

**教训（必须写进流程）**：`dependency:tree` 必须在**最终消费模块**上跑，而不是在最小探针模块上跑。
探针模块依赖少，冲突容易被掩盖；本次两个冲突都是在 `zeroai-ai-starter` 上才现形。

另外，victools 的仲裁只写在 `zeroai-ai-starter`，其它模块仍用 BOM 的 5.0.0，影响面被刻意限制在本模块内。

### 4.2 运行期装配证据

`zeroai-agent-spike` 模块 3 个测试全部通过（`mvn -Pspike -pl zeroai-agent-spike test`，Tests run: 3, Failures: 0）：

| 测试 | 证明什么 |
|---|---|
| `shouldBuildHarnessAgentWithoutNetwork` | 在 Java 21 上能 build 出 `HarnessAgent`，`AutoCloseable` 正常释放 |
| `shouldCreateIndependentInstancesFromSameBuilder` | 共享 Builder + 按请求 `build()` 的官方推荐模式可行，实例互不干扰 |
| `shouldFailOnUnreachableEndpointWithoutLeakingSecret` | 端点不可达时抛可控异常，异常链中不含密钥明文 |

第三个用例的日志（已裁剪）：

```
[zeroai-spike] ERROR | OpenAIException: HTTP transport error during streaming: SSE/NDJSON stream failed: java.net.ConnectException
```

这说明**模型传输层确实被真正调用过**（打到 `127.0.0.1:1` 被拒），不是「只构造对象就宣称跑通」。

## 5. 通过 javap 确认的 API 事实（避免按文档猜测）

以下结论来自 `javap` 读取 `agentscope-harness-2.0.4.jar` / `agentscope-core-2.0.4.jar` / `agentscope-extensions-model-openai-2.0.4.jar`：

| 事实 | 细节 |
|---|---|
| `HarnessAgent implements Agent, AutoCloseable` | 必须 `try-with-resources`，否则线程/资源泄漏 |
| `HarnessAgent.Builder` | 有 `name` / `description` / `sysPrompt` / `model(Model|String)` / `toolkit` / `workspace(Path|String)` / `agentId(String)` / `maxIters(int)` / `middleware(s)` / `stateStore` / `disableFilesystemTools()` / `disableShellTool()` / `build()` |
| **`name()` 与 `agentId()` 语义不同** | 实测：`name("x")` → `getName()` 返回 `x`；但 `agentId("x")` **不影响** `getAgentId()`，后者返回每次 `build()` 生成的 UUID |
| `RuntimeContext.builder().userId().sessionId().build()` | 另有 `put(String,Object)` / `get(...)`，可安全携带服务端身份，模型看不到 |
| `OpenAIChatModel.builder()` | `apiKey` / `baseUrl` / `modelName` / `stream` / `formatter(new OpenAIChatFormatter())` / `httpTransport` / `proxy` / `contextWindowSize` |
| 事件类型 | `io.agentscope.core.event.AgentEventType`；`TextBlockDeltaEvent#getDelta()`、`ToolCallStartEvent#getToolCallName()` |
| 工具 | `io.agentscope.core.tool.Toolkit` + `@Tool` / `@ToolParam` 反射注册，或继承 `ToolBase` |

**`agentId` 语义差异是本次最重要的发现**：官方 quickstart 说「重启时保留相同 `agentId` 即可恢复会话」，
但 `getAgentId()` 实际是实例级 UUID。这意味着会话恢复的稳定身份**不能依赖 `getAgentId()`**，
必须靠 `RuntimeContext` 的 `userId` + `sessionId`。该结论直接影响 Phase 4 的会话设计，
需要在 Phase 4 用真实调用再确认一次（当前无密钥，只能算强怀疑，不是定论）。

## 6. 未通过 / 阻塞项

| 验收项 | 状态 | 原因 |
|---|---|---|
| 一次正常模型调用成功 | **BLOCKED** | 密钥已由用户在 `.env` 提供，但被 DeepSeek 服务端判为无效（见下方证据） |
| API Key 错误时返回可控错误 | **已通过** | 真实 401 路径已验证，异常被包装、密钥未泄漏 |
| 首 Token 延迟 / Token 成本 | BLOCKED | 依赖一次成功的调用 |

**2026-10-26 补测证据（有密钥后）**：

```
[INFO] baseUrl=https://api.deepseek.com model=deepseek-chat
WARN  JdkHttpTransport -- HTTP request failed. URL: https://api.deepseek.com/v1/chat/completions
      Status: 401 | Authentication Fails, Your api key: ****e5a6 is invalid
ERROR AuthenticationException: HTTP transport error during streaming: ... status 401
```

用 `curl https://api.deepseek.com/models` 直连交叉验证，**同样 401**，因此：

- 这不是 AgentScope 的兼容性问题，也不是 baseUrl/协议拼接问题
  （AgentScope 正确拼出了 `/v1/chat/completions`，TLS 与请求体均正常）；
- 是 `.env` 里 `LLM_API_KEY` 本身在 DeepSeek 侧无效（格式检查：`sk-` 前缀、总长 38、无引号/空格/CRLF，格式无异常）。

结论：**这是真实模型调用路径上唯一剩下的阻塞，且必须由用户更换密钥解决，不能通过改代码绕过。**
在密钥修复前，任何「模型调用成功 / 首 Token 延迟 / token 成本」的数字都不会被记录。

密钥修复后重跑：

```bash
set -a && . ./.env && set +a
export AGENTSCOPE_API_KEY="$LLM_API_KEY" AGENTSCOPE_BASE_URL="$LLM_BASE_URL" AGENTSCOPE_MODEL="$LLM_MODEL"
mvn -Pspike -pl zeroai-agent-spike exec:java -Dexec.mainClass=com.zeroai.agent.spike.AgentScopeSpike
```

## 7. 遗留风险

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| 1 | spike 依赖树中同时出现 `okhttp 4.12.0` 与 `okhttp-jvm 5.3.2`（包名重叠） | 若同时进入同一 classpath，可能出现类冲突/运行时 `NoSuchMethodError` | 当前 `zeroai-mobile-api` 只解析到 `okhttp 4.12.0`；Phase 2 接入时必须重新跑 `dependency:tree` 复查，必要时对 MCP 相关传递依赖做**局部**排除 |
| 2 | AgentScope 自带 `AgentTraceMiddleware` 会把模型异常打到日志 | 可能与项目 logback 策略（`LOG_PATH_PREFIX`）叠加，产生噪音 | 接入时统一日志级别，禁止 debug 级输出提示词 |
| 3 | `agentscope-core` 依赖 `opentelemetry-reactor-3.1:2.27.0-alpha` | alpha 版可观测组件，行为不稳定 | 不启用 OTel 导出即无影响；不主动引入 exporter |
| 4 | 4 个测试仅覆盖装配与错误路径 | 不足以证明业务可用 | Phase 2/3 用 mock 模型补齐工具调用链路测试 |

## 8. 备选方案与被否原因

| 备选 | 否掉原因 |
|---|---|
| 升级 Spring Boot / Spring AI 以对齐 AgentScope | 方案明确禁止；且当前版本已完全对齐，升级无收益、风险高 |
| 用 `agentscope-spring-boot-starter` 一把接入 | 会接管 `Model` Bean 和 `agentscope.*` 配置，与既有 `zeroai.ai.*` 双轨冲突，且难以在关闭时不建 Bean |
| 引入 `2.1.0-BETA1` | beta，方案要求用当前稳定版 |
| 全局 `<exclusions>` 抹平冲突 | 方案禁止；且实测没有需要抹平的版本冲突 |
| 把 AgentScope 放到独立进程、用 HTTP/MCP 接入 | 是兼容性失败时的兜底路线；当前不需要，但如果 Phase 2 在真实 `mobile-api` classpath 上出现无法局部解决的冲突，再切到该方案 |

## 9. 回滚方式

本次改动是可完全删除的增量：

1. 删除模块目录 `zeroai-agent-spike/`
2. 删除根 `pom.xml` 中的 `spike` profile（只删 `<profile><id>spike</id>` 这一段，**不要动用户新增的 `sim` profile**）
3. 删除 `docs/agent/` 下本次新增文档（可选保留）
4. 无数据库变更、无业务代码变更，不需要回滚数据

验证回滚干净：`mvn -B test` 全量通过，`git status` 不出现 spike 相关条目。

## 10. 状态更新（Phase 4 完成后）

此前记为「真实模型调用 BLOCKED（密钥被上游判为无效）」的结论**已作废**，原因不是密钥失效：

`.env` 中 `LLM_API_KEY` 写成了 `sk-sk-...`（长度 38，正常 35），DeepSeek 收到的是非法字符串，
返回 `Authentication Fails, Your api key: ****e5a6 is invalid`。修正为单个 `sk-` 后全部通过：

| 验证项 | 结果 |
|---|---|
| `/models` 鉴权 | 200，返回 `deepseek-flash`（V4.1-Flash，1M 上下文）与 `deepseek-v4-pro` |
| `chat/completions` 文本 | 正常 |
| `tool_calls`（Function Calling） | `finish_reason=tool_calls`，参数 `{"keyword":"手机","maxPrice":5000}` 解析正确 |
| Harness 事件流 | `streamEvents` 正常产出 `TextBlockDeltaEvent` / `ToolCallStartEvent` / `ToolResultEndEvent` |
| 取消能力 | `Disposable.dispose()` + `agent.interrupt()` 生效，实测 `signal=cancel` |

同时确认一个此前未记录的配置事实：**Spring Boot 不会自动读取 `.env`**。
现通过 `spring.config.import: optional:file:./.env[.properties]` 加载，环境变量仍可覆盖（优先级更高）。

完整端到端记录见 [`PHASE-4-event-stream.md`](./PHASE-4-event-stream.md)。
