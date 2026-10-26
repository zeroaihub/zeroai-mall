# BASELINE — zeroai-mall Agent 接入基线审计（Phase 0）

> 产出时间：2026-10-26
> 执行依据：`docs/zeroai-mall-AgentScope-Workbuddy-实施方案.md` Phase 0
> 原则：所有结论以当前工作区代码与构建结果为准，文档口径只作为线索。

## 1. 工具链与版本事实

| 项 | 实测值 | 说明 |
|---|---|---|
| 默认 `java` | 24.0.2 (Corretto) | **不是项目要求版本**，构建必须显式指定 JAVA_HOME |
| 构建用 JDK | 21.0.12 (ms-21.0.12) | 路径 `~/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home` |
| Maven | 3.9.9 | |
| `java.version` | 21 | 根 pom |
| `spring-boot.version` | 4.1.2 | 根 pom |
| `spring-ai.version` | 2.0.2 | 根 pom |
| 项目版本 | 2.0.0 | |

**注意**：本机默认 JDK 24 与项目 `java.version=21` 不一致，直接 `mvn` 会走到 24。所有构建命令必须带
`export JAVA_HOME=<ms-21.0.12>`。这是环境事实，不是代码缺陷。

## 2. 未提交改动的保护清单

`git status --short --branch` → `## main`，HEAD `7654817 本地提交`。当前存在以下未提交改动，
**本轮改造不得覆盖、清理或重置**：

| 文件 | 归属 | 说明 |
|---|---|---|
| `pom.xml` | 用户 | 新增 `sim` profile，把 `zeroai-simulator` 挂在 profile 下，默认构建不包含 |
| `.gitignore` | 用户 | 补 `logs/`、`*.log`、`.tmp*`、`.DS_Store`、`node_modules/` 等 |
| `zeroai-simulator/**` | 用户 | 新增多用户并发压测 CLI 模块（未跟踪） |
| `docs/SIMULATOR_P0_多用户并发仿真实施方案.md` | 用户 | 未跟踪 |
| `docs/zeroai-mall-AgentScope-Workbuddy-实施方案.md` | 用户 | 未跟踪，本次执行依据 |
| `docker-compose.local.yml`、`mysql/conf/my.cnf.dev`、`scripts/dev/init-db-local.sh`、`es/config/elasticsearch.dev.yml` | 本地环境适配 | 上一轮为在本机跑通加的，未提交 |
| `zeroai-*/src/main/resources/logback-spring.xml` | 本地环境适配 | `logPath` 改为 `${LOG_PATH_PREFIX:-/home/logs}`，否则 macOS 起不来 |
| `es/plugins/**` | 副作用 | 仅文件模式 644→755，内容零变更 |

> `es/plugins/**` 的 mode 变更是上一轮给 ES 挂载目录补权限造成的噪音，不是用户业务改动；
> 建议在提交前用 `git checkout -- es/plugins` 单独还原模式，避免污染 diff。

## 3. 模块图（根 pom `<modules>`）

```
zeroai (2.0.0, Java 21, Boot 4.1.2, Spring AI 2.0.2)
├── zeroai-common            通用配置/切面/基础设施
├── zeroai-util              枚举/异常/常量/轻量工具
├── zeroai-data              ├── zeroai-data-redis
│                            └── zeroai-data-elastic
├── zeroai-message           ├── zeroai-message-core
│                            └── zeroai-message-consumer
├── zeroai-payment-channel   微信/支付宝/易支付适配
├── zeroai-domain-api        跨领域契约（接口 + DTO/VO）
├── zeroai-domain-goods / cart / promotion / inventory / trade
├── zeroai-ai-starter        Spring AI 通用能力（业务无关）
├── zeroai-admin-api         后台入口 + @Scheduled 治理任务
├── zeroai-mobile-api        H5 入口（Sa-Token 鉴权）
└── [profile sim] zeroai-simulator   压测 CLI，默认不构建
```

`zeroai-mobile-api` 依赖 `zeroai-ai-starter`（已存在依赖边）。

## 4. 现有 AI 能力（代码事实）

模块：`zeroai-ai-starter`，全部源码 8 个类：

| 类 | 事实 |
|---|---|
| `AiAgentProperties` | 前缀 `zeroai.ai`；`enabled` 默认 **false** |
| `AiAgentAutoConfiguration` | `@ConditionalOnProperty(zeroai.ai.enabled=true)`；构建 `OpenAiChatModel` + `ChatClient` |
| `AiChatController` | `GET ${zeroai.ai.chat-path:/ai/chat}?q=`，返回 `SseEmitter` |
| `AiEvent` | `record AiEvent(String type, Object data)` |
| `AiEventType` | `thinking` / `tool_result` / `message_delta` / `product_cards` / `confirm_action` / `done` / `degrade` / `error` |
| `AiUserContext` | SPI，默认兜底实现返回 `null` |
| `PromptLoader` | 从 classpath 加载 `prompts/guide-system.st` |

配置项实测（`AiAgentProperties` 字段）：`provider`（默认 openai）、`baseUrl`（默认 `https://api.deepseek.com`）、
`apiKey`、`model`（默认 `deepseek-chat`）、`temperature=0.3`、`maxTokens=1024`、
`systemPrompt=prompts/guide-system.st`、`chatPath=/ai/chat`、`sseTimeoutMs=120000`、
`maxSteps=6`、`maxToolCallsPerTurn=6`、`maxHistoryTurns=8`、`toolTimeoutMs=3000`、`degradeOnError=true`。

### 关键缺口（必须如实记录）

1. **没有 Tool Calling**。`ChatClient` 构建时未注册任何工具，`AiChatController` 直接 `prompt().user(q).stream()`。
   方案 §1.1 的说法在代码中成立：现有 starter **不具备**业务 Tool Calling 能力。
2. **`AiUserContext` 无实现**。mobile-api / admin-api 均未提供实现类，注入的工具拿不到用户身份。
3. **`confirm_action`、`tool_result`、`product_cards`、`degrade` 事件只定义了常量，没有产生方**。
   当前只有 `message_delta` / `error` / `done` 三个事件真正会被发出（`done` 的 `steps` 恒为 0）。
4. 无会话概念：`sessionId` 不存在，无持久化，无 checkpoint。
5. `apiKey` 缺失时 `AiAgentAutoConfiguration` 抛 `IllegalStateException`（仅当 enabled=true）。

## 5. 鉴权现状

`MobileSaTokenConfiguration`：
- 拦截器 `/**` 全拦，白名单 `EXCLUDE_PATHS` 列了 30 项（`/home/**`、`/search/**`、`/goods/detail/**`、
  `/login`、`/seckill/**`、`/pay/callback/**` 等）。
- **`/ai/**` 不在白名单内** → `/ai/chat` 当前默认要求登录。✅ 符合方案 §Phase 0 第 3 条要求。
- 身份读取入口：`MobileSecurityUtils.getUserId()`（未登录返回 `null`）、`getLoginUser()`（抛 `BusinessException(UNAUTHORIZED)`）。
- 登录态存于 Sa-Token session，`isConcurrent=true`、`isShare=false`，每端独立 token。

## 6. 可复用的只读业务能力（Phase 3 工具候选）

| 方案要求工具 | 现有可复用入口 | 结论 |
|---|---|---|
| `searchProducts` | `SearchApplicationService`（**在 mobile-api 内**，直连 `ElasticDocument` + ES QueryBuilder） | 可用，但实现位于入口层，跨模块复用需要端口/适配 |
| `getProductDetail` | `IGoodsDetailService.getGoodsDetailData(goodsId)` → `GoodsDetailResponseVO` | 可用，`zeroai-domain-api` 契约 |
| `getStockStatus` | `IGoodsProductService`（SKU 维度，含 `number`/`locked_stock`） | 可用，需裁剪字段，**不得暴露内部流水** |
| `myOrders` | `IMobileOrderService.selectListPage(page, showType, userId)`、`statusCount(userId)` | 可用，**userId 必须由调用方注入** |
| `trackLogistics` | 全仓检索 `logistics`/`物流`/`express`/`shipping` **零命中** | **能力缺口**，MVP 不实现，README 说明 |

其它事实：
- `IMobileOrderService.getOrderDetailByOrderSn(orderSn)` **内部不校验归属**，工具层必须自行校验 `order.userId == currentUserId`。
- `IMobileOrderService` 同时暴露 `submit`/`cancel`/`refund`/`confirm`/`delete` 等写方法，
  这些**不得**出现在任何 Agent 工具白名单中。

## 7. 构建与测试基线（改动前）

| 命令 | 结果 |
|---|---|
| `mvn -pl zeroai-ai-starter -am clean package` | BUILD SUCCESS，1.4s |
| `mvn test`（全量 19 模块） | **BUILD SUCCESS**，16.6s |

测试现状：`src/test/java` 下共约 20 个测试类，集中在 mobile-api 控制器/配置、
domain-promotion 秒杀与优惠券、message-consumer、common mapper 扫描。
均为轻量单测，**不依赖 MySQL/Redis/ES/RabbitMQ**，因此全量测试可在无中间件环境下通过。

> 日志里出现的 `java.lang.RuntimeException: redis down` 来自
> `MessageConsumerTemplateTest` 的负向用例夹具，属于预期行为，不是失败。

## 8. 环境依赖（跑真实链路时才需要）

| 组件 | 本地端口 | 容器名 |
|---|---|---|
| MySQL 8 | 3307 | zeroai-mysql |
| Redis | 6380 | zeroai-redis |
| RabbitMQ（含 delayed-message 插件） | 5673 / 15673 | zeroai-rabbitmq |
| Elasticsearch 7.14（ik + pinyin） | 9200 | zeroai-es |

启动：`docker compose -f docker-compose-es7-rabbitmq-redis-mysql.yml -f docker-compose.local.yml up -d`
（宿主机 3306/6379/5672 已被其它实例占用，故全部错开端口）。

## 9. AgentScope Java 版本核实（Phase 1 前置）

Maven Central `io.agentscope` 实际存在的坐标（已联网核对）：

- `agentscope` / `agentscope-core` / `agentscope-harness` / `agentscope-bom`
- `agentscope-extensions-model-openai`（OpenAI 兼容）
- `agentscope-extensions-session-mysql` / `-redis` / `agentscope-extensions-jdbc`
- `agentscope-spring-boot-starter` 系列

版本序列（`agentscope-harness` 与 `agentscope-extensions-model-openai` 一致）：
`2.0.0 → 2.0.1 → 2.0.2 → 2.0.3 → 2.0.4 → 2.1.0-BETA1`

**结论：当前稳定版取 `2.0.4`；`2.1.0-BETA1` 为 beta，不采用。**
`<latest>` 元数据指向 BETA1，不能直接用它作为版本依据。

## 10. Phase 0 遗留问题清单

| # | 问题 | 处理 |
|---|---|---|
| 1 | 默认 JDK 24 与项目 21 不一致 | 所有构建显式设 `JAVA_HOME`，已记录 |
| 2 | `es/plugins/**` mode 噪音 | 提交前单独还原，不属本轮改动 |
| 3 | 无物流能力 | Phase 3 记录缺口，MVP 跳过 |
| 4 | 无 Tool Calling / 无 session | 正是本次要建的能力 |
| 5 | `AiUserContext` 无实现 | Phase 2/3 在 mobile-api 侧补齐 |
| 6 | 无真实模型密钥（环境变量未配置） | Phase 1 真实调用需用户提供；无 Key 时按「环境阻塞」记录，不伪造通过 |

## 11. Phase 6 后现状快照（2026-10-26 更新）

本文档 §1–§10 是 **Phase 0 的改动前审计**，数字与结论保持原样不追改。各阶段完成后的现状见下表：

| 项 | Phase 0 审计值 | Phase 5 后 | Phase 6 后（当前） |
|---|---|---|---|
| AI 通道 | 仅 Spring AI 单轮问答，无工具、无会话 | 双通道 + 4 只读工具 + 会话状态 | 同左，另可选**写意图提议链路**（HITL） |
| ai-starter 源码类 | 8 个 | 25 个 | **40 个**（新增 `ai/mall/proposal` 包、ActionProposalController、3 个工具） |
| ai-starter 测试 | 无（0） | 64 个用例全绿 | **90 个**用例全绿 |
| 全量构建 | BUILD SUCCESS，16.6s | 19 模块 BUILD SUCCESS | 19 模块 BUILD SUCCESS |
| 工具调用能力 | 无 | 4 个只读商城工具，写操作零注册 | 6 个只读 + `propose_order`（**只写提议表**，不碰订单）；仍无 create/submit/pay |
| 写操作 | 无 | 无 | **两段式 HITL**：提议落 `ai_action_proposal`，订单写入仅由 `POST /ai/action/confirm/{proposalNo}` 触发，内部只调原入口 `asyncSubmit`；默认 `AI_ACTION_ENABLED=false` |
| 会话 | 无 sessionId | 服务端派生 `u<userId>` | 不变 |
| 事件 | 只有 `message_delta`/`error`/`done` | + `thinking`/`tool_result` | + `confirm_action`（proposalNo/实付价/确认路径） |

**度量基线**：见 `docs/agent/BASELINE-METRICS.md`（12 题评测集、24 条/组、20 轮长会话；
默认配置下工具选择 12/12、越权泄漏 0、拒答 4/4、错误率 0；单轮 token 中位 8166、步数 P95 6）。
评测集跑于 Phase 6 之前、工具面为 4 只读时；Phase 6 工具面扩到 6+1，**基线需在下次改动后回跑对照**。

**HITL 详细设计与实测**：见 `docs/agent/PHASE-6-hitl.md`（状态机、并发裁决、实测矩阵、回滚方式）。

> 注意：§7「构建与测试基线」里的「`mvn test` 16.6s / 213 测试」仍是 Phase 0 的历史值，
> 现在含 ai-starter 的 90 个用例，`mvn test` 会明显变长。
