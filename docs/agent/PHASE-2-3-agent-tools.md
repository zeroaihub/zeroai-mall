# Phase 2 / Phase 3 — AgentScope 适配层与只读商城工具

> 状态：Phase 2 **PASS**，Phase 3 **PASS（物流能力缺失，已记录为能力缺口）**，
> Phase 1 的「一次真实模型调用」仍 **BLOCKED（密钥被上游判为无效）**。

## 1. 模块归属决策（与最初方案的偏差）

方案原定新建 `zeroai-ai-mall`，**实际改为全部落在 `zeroai-ai-starter`**，由用户指定。
这带来一个必须记录的定位变化：

| 项 | 变更前 | 变更后 |
|---|---|---|
| 模块定位 | 通用 AI starter，可复用到其他项目 | 商城 AI 模块 |
| 业务依赖 | 无 | `zeroai-domain-api`（optional） |

原因：`zeroai-domain-api` 并非纯契约包，它自带 `spring-boot-starter-web` / `amqp` /
`mybatis-plus` 与一批 `Mapper` 接口。一旦 ai-starter 依赖它，「零业务依赖」这条描述就不再成立。

因此加了三道约束，避免模块继续腐化：

1. 业务相关代码只允许出现在 `com.zeroai.ai.mall.tool` 与 `com.zeroai.ai.agent` 两个包；
   `com.zeroai.ai.config` / `web` / `prompt` 保持与业务无关。
2. `domain-api` 声明为 `optional`，不向本模块的消费者透传整套持久化栈。
3. 检索能力（实现位于入口层）用端口 `MallSearchPort` 反转依赖，**禁止** ai-starter 反向依赖 `zeroai-mobile-api`。

## 2. 新增文件

### `zeroai-ai-starter`

| 文件 | 职责 |
|---|---|
| `config/AiAgentProperties.java`（改） | 新增 `engine` / `agentWorkspace` / `agentSystemPrompt` |
| `config/AiAgentAutoConfiguration.java`（改） | 修复同步/异步客户端缺失问题，见 §5 |
| `agent/AgentScopeAutoConfiguration.java` | 双条件装配（`enabled=true` 且 `engine=agentscope`），否则零 Bean |
| `agent/MallAgentFactory.java` | 每请求构建 Agent，按 userId 装配工具集 |
| `agent/MallAgentContext.java` | `record(userId, sessionId)`，userId 必须来自服务端 |
| `mall/port/MallSearchPort.java` | 检索端口（含 `GoodsBrief` 记录） |
| `mall/tool/SearchProductsTool.java` | `search_products` |
| `mall/tool/GoodsDetailTool.java` | `get_product_detail` |
| `mall/tool/StockStatusTool.java` | `get_stock_status` |
| `mall/tool/MyOrdersTool.java` | `list_my_orders` |
| `mall/tool/ToolJson.java` | 无依赖 JSON 拼装（规避 Jackson 2/3 双线） |
| `resources/prompts/mall-agent-system.st` | Agent 专用提示词，明写只读边界与禁止代下单 |

### `zeroai-mobile-api`

| 文件 | 职责 |
|---|---|
| `api/ai/MallSearchPortImpl.java` | 复用 `SearchApplicationService`，`memberId` 传 null 避免写搜索历史 |
| `api/ai/MobileAiUserContext.java` | 从 Sa-Token 取 userId，替换原来的 null 兜底 |

## 3. 安全设计（比方案要求更严的一点）

方案要求「工具方法显式接收 userId 参数」。**实际实现改为构造期注入**：

```java
// 模型永远看不到 userId 这个参数
new MyOrdersTool(mobileOrderService, context.userId())
```

- 匿名请求（userId=null）**根本不注册** `list_my_orders`，越权在对象层面不可能发生，
  而不是靠「模型不会乱传参数」这种约定。
- `sessionId` 仅用于会话连续性，**不作授权凭据**；换 sessionId 拿不到别人的数据。
- 工具清单里不存在任何写操作：无下单、无支付、无退款、无库存写。
  `StockStatusTool` 虽注入了 `IGoodsProductService`（该接口含写方法），但只调用 `getById`，
  已在类注释中明确约束；若需更严隔离可改为只依赖 `IGoodsDetailService`。

## 4. 测试

```
mvn -pl zeroai-ai-starter -am test
  MallAgentFactoryTest  5 passed
  MallToolTest          7 passed

mvn test（全量，默认 profile）
  admin-api 20 / ai-starter 12 / mobile-api 15 / message-consumer 8 —— BUILD SUCCESS
```

覆盖点：匿名无订单工具 / 登录有订单工具 / 缺密钥报错不含密钥值 / 无 file·shell 工具 /
端口缺失显式降级 / 关键词空校验 / limit 收敛到 10 / JSON 转义。

## 5. 本阶段发现并修复的三个真实缺陷

| # | 缺陷 | 影响 | 修复 |
|---|---|---|---|
| 1 | victools 版本冲突：Spring AI 要 5.0.0，AgentScope 要 4.38.0，均 compile | 构建 Agent 抛 `NoSuchMethodError: generateSchema` | 钉 4.38.0（仅 ai-starter 生效，影响面受限）。详见 ADR-001 §4.1.1 |
| 2 | okhttp 4.12.0 与 okhttp-jvm 5.3.2 同包名共存 | 潜在 `NoSuchMethodError` | 从 agentscope 依赖排除 `okhttp-jvm`，统一 4.12.0 |
| 3 | `OpenAiChatModel` 只注入同步客户端 | **AI 开启即启动失败**：`At least one credential source must be specified`。此前 AI 开关从未打开，缺陷一直隐藏 | 同步 + 异步客户端同时注入 |

## 6. 运行期验证（真实启动，非 mock）

| 场景 | 结果 |
|---|---|
| `zeroai.ai.enabled=false`（默认） | 启动 3.4s，日志中 `agentscope` / `MallAgentFactory` 出现 **0 次** |
| 未登录访问 `/ai/chat` | `{"code":401}`，Sa-Token 拦截生效 |
| `enabled=true` + `engine=agentscope` | 启动 3.2s，无 linkage 错误，工厂 Bean 构造成功 |
| 登录后调用 `/ai/chat` | 收到 `event:error` 契约事件；服务端为上游 `401 UnauthorizedException`，**非本地兼容性问题** |
| `AiUserContext` | 日志 `userId=93`，证明新实现已替换 null 兜底 |

## 7. 未完成 / 风险

| 项 | 状态 |
|---|---|
| 真实模型调用成功、首 Token 延迟、token 成本 | **BLOCKED**，`.env` 中 `LLM_API_KEY` 被 DeepSeek 判为无效；curl 交叉验证同样 401 |
| Phase 4：AgentScope 事件 → `AiEventType` 映射、控制器切换 | 未开始。当前 `/ai/chat` 仍走 Spring AI 通道 |
| 物流查询 `trackLogistics` | 能力缺口：全仓无物流实现，MVP 不做，不造假 |
| AI 关闭时 `/ai/chat` 返回 `{"code":500}` 而非 404 | 既有行为，非本次引入，建议后续统一为 404 |

## 8. 回滚

```bash
# 1) 删除本阶段新增文件
rm -rf zeroai-ai-starter/src/main/java/com/zeroai/ai/{agent,mall}
rm -f  zeroai-ai-starter/src/main/resources/prompts/mall-agent-system.st
rm -f  zeroai-mobile-api/src/main/java/com/zeroai/mobile/api/ai/{MallSearchPortImpl,MobileAiUserContext}.java
rm -rf zeroai-ai-starter/src/test/java/com/zeroai/ai/{agent,mall}

# 2) 还原 pom
git checkout -- zeroai-ai-starter/pom.xml pom.xml

# 3) 还原既有文件
git checkout -- zeroai-ai-starter/src/main/java/com/zeroai/ai/config/{AiAgentAutoConfiguration,AiAgentProperties}.java
git checkout -- zeroai-ai-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

无数据库变更、无接口契约变更、无前端改动，回滚后应用行为与接入前一致。
