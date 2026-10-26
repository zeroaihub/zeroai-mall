# Phase 6 — 写操作 Human-in-the-loop：下单提议与独立确认

- **日期**：2026-10-26
- **前置**：Phase 0–5 全部通过（只读工具链稳定、事件协议稳定、基线已跑通）
- **本阶段性质**：**功能交付**。Agent 第一次获得「表达写意图」的能力，但**写执行权始终在用户手里**。
- **结论**：提议/确认两段式闭环打通。Agent 只写提议表；订单写入只能由独立确认接口触发，
  且严格调用原下单入口 `IMobileOrderService.asyncSubmit`。重复确认、并发双击、过期、
  价格变化、库存不足、跨用户确认全部实测拒绝；未确认提议不会产生订单。

---

## 1. 一句话设计

> **Agent 无写工具。它只能生成一张「待确认的下单提议」（`ai_action_proposal`）；
> 订单真正落库，只能由用户登录态下调用 `POST /ai/action/confirm/{proposalNo}`，
> 而该接口内部只调用商城原有的 `asyncSubmit`，一行下单逻辑都不复制。**

```
用户："帮我下单"
   │
   ▼
Agent → propose_order 工具 ──写──▶ ai_action_proposal (PENDING)
   │                                    │
   ▼                                    │ 过期 TTL=600s
SSE: confirm_action 事件               ▼
   │  (proposalNo / 实付价 / 确认路径)  EXPIRED（惰性）
   ▼
用户点「确认」POST /ai/action/confirm/{proposalNo}
   │
   ├─ 条件 UPDATE 抢锁 PENDING→CONFIRMING（数据库裁决，防并发双击）
   ├─ 重校验：归属 / 过期 / 购物车比价比量 / 库存 / 优惠券 / 价格
   ├─ 调原入口 IMobileOrderService.asyncSubmit(...)  ← 唯一写订单的地方
   └─ CONFIRMING→CONFIRMED（落 orderSn）
        │ 业务拒绝 → REJECTED
        │ 系统异常 → 回滚 CONFIRMING→PENDING（可重试，原入口有购物车指纹幂等兜底）
```

---

## 2. 状态机与并发裁决

状态：`PENDING → CONFIRMING → CONFIRMED | REJECTED`；`PENDING → EXPIRED`（惰性）；
`CONFIRMING → PENDING`（仅系统异常回滚）。

| 迁移 | 实现 | 互斥依据 |
|---|---|---|
| PENDING→CONFIRMING | `claimPending`：`UPDATE ... WHERE status='PENDING' AND expire_time > now` | 返回受影响行数，0 行即抢锁失败 |
| CONFIRMING→CONFIRMED | `markConfirmed`（`asyncSubmit` **返回之后**才执行） | 同上条件更新 |
| CONFIRMING→REJECTED | `markRejected(fail_code)` | 业务拒绝 |
| CONFIRMING→PENDING | `revertToPending` | 仅 `RuntimeException`/系统异常 |
| PENDING→EXPIRED | `markExpired`（查询/确认时惰性） | 过期判定**看时间而非落库状态** |

**幂等双层**：
1. DB 唯一键 `(user_id, idempotency_key)`，幂等键 = SHA-256(购物车指纹+地址+优惠券)。同一快照重复「生成提议」返回同一 proposalNo，不写第二行。
2. 原下单入口自带 Redis 购物车指纹幂等（`ORDER_SUBMIT_DEDUP_KEY`）——系统异常回滚后用户重试，也不会重复下单。

**「先返回成功再补写」是被禁止的**：`markConfirmed` 一定在 `asyncSubmit` 正常返回后执行；
异常路径只落 `REJECTED`/回滚 `PENDING`，**任何情况下都不回写 orderSn**。

---

## 3. 安全边界检查（对照方案验收项）

| 验收项 | 结论 | 证据 |
|---|---|---|
| Agent 无直接写工具 | ✅ | 工具面：匿名 3 只读 + 登录 3 只读 + `propose_order`（只写提议表）。无 `create_order`/`submit_order`/`pay_order`，`MallAgentFactoryTest` 断言 |
| 工具内部不直接执行写操作 | ✅ | `propose_order` 只 `INSERT ai_action_proposal`；测试 `createProposalNeverSubmitsOrder` 断言 `asyncSubmit` 零调用 |
| 未确认提议不创建订单 | ✅ | 实测：生成提议后订单表数量不变 |
| 确认后只走原业务写入口 | ✅ | `submitThroughOrigin` 唯一出口是 `asyncSubmit(OrderCommitReqVO, userId)`；价格/运费/优惠只做**重校验**不重算下单 |
| 重复确认不产生重复订单 | ✅ | 实测：第 2 次返回 `ALREADY_CONFIRMED` 并携带原 orderSn，订单表无新增 |
| 过期/价格变化/库存不足/跨用户均拒绝且可读 | ✅ | 见 §6 实测矩阵，文案走 `ProposalRejectCode` 中文枚举 |
| 跨用户与探测 | ✅ | 跨用户查询/确认一律 `PROPOSAL_NOT_FOUND`（与不存在共用一码，不暴露他人提议是否存在） |
| 越权写库 | ✅ | userId 只从 `AiUserContext` 取，接口不接受参数用户标识 |
| 开关门禁 | ✅ | `@ConditionalOnExpression("${zeroai.ai.enabled:false} && ${zeroai.ai.action.enabled:false}")`，关闭时 Controller/Service/工具**整个不注册** |
| 先成功后补写 | ✅ | 见 §2，异常路径绝不回写 orderSn |
| 原有订单/库存/支付/消息消费者回归 | ✅ | `mvn test` 19 模块 BUILD SUCCESS，ai-starter 90 用例全绿 |

---

## 4. 变更文件清单

### 4.1 新增（DDL）

| 文件 | 说明 |
|---|---|
| `db-init/ai_action_proposal.sql` | 增量 DDL。核心列：`proposal_no`(unique)、`user_id`、`action_type`、`status`、`idempotency_key`、`address_id`、`user_coupon_id`、`cart_ids`、`items_snapshot`、四段价格、`expire_time`/`confirm_time`/`order_sn`/`fail_code`。唯一约束 `(user_id, idempotency_key)`；索引 `(user_id, status, expire_time)` |
| `db-init/ai_action_proposal_rollback.sql` | `DROP TABLE IF EXISTS ai_action_proposal;` |

> **未复用既有表**：先查过 `shop_order`/`shop_cart`/`local_message` 等，没有承载「待确认意图」的表；
> 订单表是已成交语义，不能拿来做预览，故按方案允许新增 `ai_action_proposal`。

### 4.2 新增（AI 代码，全部在 `zeroai-ai-starter`）

| 文件 | 职责 |
|---|---|
| `ai/mall/proposal/ProposalStatus.java` | 状态枚举 + 合法迁移图注释 |
| `ai/mall/proposal/ProposalRejectCode.java` | 拒绝码 + 中文可读文案 |
| `ai/mall/proposal/ProposalItem.java` | 商品行 record；制表符分隔序列化（刻意避开 Jackson 2/3 双版本线） |
| `ai/mall/proposal/entity/AiActionProposal.java` | MyBatis-Plus 实体 |
| `ai/mall/proposal/mapper/AiActionProposalMapper.java` | 全部状态迁移走条件 UPDATE |
| `ai/mall/proposal/ActionProposalProperties.java` | `zeroai.ai.action` 前缀；`enabled=false`、`proposalTtlSeconds=600`、`maxItemsPerProposal=10` |
| `ai/mall/proposal/ActionProposalService.java` | **核心**：生成提议（幂等）/ 确认（抢锁→重校验→原入口→落状态）/ 查询（跨用户隐藏） |
| `ai/mall/proposal/AiActionAutoConfiguration.java` | 双开关条件装配，与 AgentScope 引擎解耦 |
| `ai/mall/proposal/vo/ProposalVO.java` / `ConfirmResVO.java` | 对外视图；不暴露主键/幂等键/地址/联系方式 |
| `ai/web/ActionProposalController.java` | `POST ai/action/confirm/{proposalNo}`（唯一写入口）、`GET ai/action/{proposalNo}` |
| `ai/mall/tool/ProposeOrderTool.java` | Agent 侧唯一「写意图」工具；异常分级不回显内部信息 |
| `ai/mall/tool/ListMyAddressesTool.java` / `ListMyCartTool.java` | 只读辅助；姓名手机脱敏 |

### 4.3 修改

| 文件 | 改动 |
|---|---|
| `MallAgentFactory.java` | 构造器注入地址/购物车/提议服务；`create(context, proposalListener)` 重载；三层工具注册（匿名/登录/开启提议） |
| `AgentScopeAutoConfiguration.java` | 注入新依赖，启动日志增加 `actionEnabled={}` |
| `AgentChatHandler.java` | 提议监听器 → 下发 `AiEventType.CONFIRM_ACTION`（proposalNo/实付价/件数/过期时间/确认路径） |
| `AgentEventMapper.java` | 三个新工具的进度文案 |
| `prompts/mall-agent-system.st` | 能力边界改为 7 工具；新增「绝对禁止」与「关于下单提议」章节（必须告知需确认、不得承诺已下单） |
| `application.yml`（mobile-api） | `zeroai.ai.action.*` 配置块 + `AI_ACTION_ENABLED`/`AI_ACTION_TTL_SECONDS` 环境变量映射 |
| `META-INF/spring/...AutoConfiguration.imports` | 追加 `AiActionAutoConfiguration` |

### 4.4 测试（ai-starter 64 → 90，+26 与本阶段直接相关 23）

| 文件 | 用例 |
|---|---|
| `ActionProposalServiceTest`（新，18） | 未确认绝不调 asyncSubmit、同快照复用、匿名/地址越权拒绝、确认只调原入口一次、重复确认不重复下单、并发双击只一单、过期/跨用户/价格变化/库存不足/购物车数量变化/优惠券失效拒绝、业务拒绝→REJECTED、系统异常→revertToPending、惰性过期、视图不暴露内部字段 |
| `ProposeOrderToolTest`（新，5） | null userId 拒绝、缺地址不写数据、成功回调监听器、业务拒绝可读文案、系统异常只给重试提示 |
| `MallAgentFactoryTest`（改） | 断言改为 6 只读 + propose_order；新增「关闭不注册」「开启只多 propose_order 且无 create/submit/pay」「匿名即使开启也无 propose_order」 |
| `AgentChatHandlerTest`（改） | `create(any(), any())` 新签名；确认 `confirm_action` 事件下发 |
| `PlanModeAndWorkspaceTest` / `AgentToolSurfaceTest`（改） | 构造签名更新 |

---

## 5. 配置与运维

| 配置项 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `zeroai.ai.action.enabled` | `AI_ACTION_ENABLED` | `false` | 提议链路总开关（关 = 整个写意图能力消失） |
| `zeroai.ai.action.proposal-ttl-seconds` | `AI_ACTION_TTL_SECONDS` | `600` | 提议有效期 |
| `zeroai.ai.action.max-items-per-proposal` | — | `10` | 单笔提议商品种类上限 |

启动日志一行快照：`actionEnabled=true/false`，运维可核对。

> **环境变量映射注意**：Spring relaxed binding 不认自定义名 `AI_ACTION_ENABLED`，
> 必须在 `application.yml` 里写 `${AI_ACTION_ENABLED:false}` 显式映射（实测踩过这个坑）。

---

## 6. 实测验收矩阵

环境：mobile-api :82 + message-consumer，`AI_ENABLED=true AI_ENGINE=agentscope AI_ACTION_ENABLED=true`，
模型 `deepseek-flash`，用户 93（测试购物车 cart 439）。MySQL 8（Docker）。

| 场景 | 期望 | 实测结果 |
|---|---|---|
| Agent 生成提议 | SSE 下发 `confirm_action` 含 proposalNo/实付价/确认路径 | ✅ |
| 未确认前 | 订单数不变 | ✅ |
| 确认下单 | 订单创建，走原入口 | ✅ `orderSn=1793004194000478`（后续 `1793004518000480`，MQ 落单 265/269） |
| 重复确认 | 拒绝 + 原 orderSn，无重复订单 | ✅ `ALREADY_CONFIRMED` |
| 并发双击（5 路同时确认） | 只 1 单 | ✅ 1 成功 + 4 × `CONFIRMING_IN_PROGRESS`，订单表仅 1 张 |
| 过期提议 | 拒绝 | ✅ `EXPIRED`（判定看时间而非落库状态） |
| 价格变化 | 拒绝 | ✅ `PRICE_CHANGED` |
| 库存不足 | 拒绝 | ✅ `STOCK_INSUFFICIENT` |
| 购物车数量变化 | 拒绝 | ✅ `CART_CHANGED` |
| 跨用户确认 | 拒绝且不探测 | ✅ `PROPOSAL_NOT_FOUND` |
| 提议视图 | 不泄露内部字段 | ✅ 单测断言无 id/幂等键/地址/电话 |
| 端到端闭环 | 提议→确认→MQ→订单 | ✅ proposal `CONFIRMED` + orderSn `1793004518000480` + `shop_order` 269 |

补充：单测覆盖「业务拒绝→REJECTED 不重试」「系统异常→回滚 PENDING 可重试」；
异常文案分级——`BusinessException` 回显业务文案、参数错误回显、其余只给「请稍后重试」不泄漏内部信息。

---

## 7. 实测中发现的环境事实（记入避坑）

| 事实 | 影响 | 处理 |
|---|---|---|
| Docker MySQL 时钟是 UTC，宿主/JVM 是 CST，差 8 小时 | 用 SQL `NOW()` 造的测试提议会被 Java 侧判为已过期 | 造数用 `DATE_ADD(NOW(), INTERVAL 10 HOUR)`；**代码本身无此问题**（全部比较用 Java 时间戳） |
| `shop_order_goods.specifications` / `pic_url` 为 NOT NULL | 手工插入 `shop_cart` 缺这两列会让 MQ 消费者报错 | 造数时补齐：`specifications='["标准"]', pic_url=''` |
| 软删购物车 `del_flag=1` | 历史用户（如 99）查车返回 0 件是正确行为 | 换有活跃购物车的用户 93 |
| 首单成交后购物车被清空 | 提议的 cart_ids 失效报 `CART_CHANGED` | 每个用例独立造车，勿跨用例复用 |

---

## 8. 已知限制与未解决问题

1. **价格重校验不是实时报价接口**：确认时按现有商品表+优惠规则重算并比对，若运营侧有「下单瞬间才生效的活动价」，以 `asyncSubmit` 内部最终计算为准——我们只拦「明显不一致」，不在提议层复制定价。
2. **提议只覆盖「购物车下单」一种动作**（`action_type=ORDER_FROM_CART`）。支付、退款、改库存等写操作本阶段**不做**，继续保持只读或人工路径。
3. **`CONFIRMING` 状态无超时自动回滚**：若进程在 `claimPending` 之后、`markConfirmed` 之前崩溃，提议会停在 `CONFIRMING`，需人工或定时任务扫描回滚（当前靠日志 + 手工 SQL）。列入后续运维项。
4. **优惠券重校验依赖现有券服务语义**，若券服务后续增加「仅下单瞬间可核销」类规则，需同步 `revalidate`。
5. 测试产生的订单 `shop_order` 265/268/269（用户 93）仍留在本地验证库，属正常业务表数据，不主动删（涉及库存与订单明细一致性）；`p6-*` 测试提议与 cart 439 已在收尾时清理。

---

## 9. 回滚方式

**最快路径（不删代码，立刻关停）**：

```bash
# 置 false 重启 mobile-api 即可——Controller/Service/工具整体不装配
AI_ACTION_ENABLED=false
```

关闭后：Agent 工具面回到 6 个只读工具，`/ai/action/**` 404，已生成的 PENDING 提议无法确认（无副作用，自然过期）。

**彻底回滚（含表）**：

```bash
mysql -h127.0.0.1 -P3307 -uroot -p zeroai_mall < db-init/ai_action_proposal_rollback.sql
```

代码侧删除 `com.zeroai.ai.mall.proposal` 包、`ActionProposalController`、`ProposeOrderTool`/`ListMyCartTool`/`ListMyAddressesTool`，
并还原 `MallAgentFactory`/`AgentChatHandler`/`AgentScopeAutoConfiguration`/`application.yml`/`imports`/`prompts` 即可；
引擎切回 `AI_ENGINE=spring-ai` 时，即便提议表还在，确认接口也已不再注册（`AiActionAutoConfiguration` 独立开关）。

---

## 10. 与方案验收项对照（全项）

| 方案要求 | 状态 |
|---|---|
| Agent 仅生成 ActionProposal，工具内不直接写 | ✅ |
| 先查可复用表，没有再新增，提供增量 DDL 与回滚脚本 | ✅ `db-init/ai_action_proposal.sql` + `_rollback.sql` |
| 提议保存用户/动作/参数/价格摘要/创建过期/状态/幂等标识 | ✅ 表结构全覆盖 |
| 独立确认接口、严格调用现有写入口、不复制业务逻辑 | ✅ `POST ai/action/confirm/{proposalNo}` → `asyncSubmit` |
| 确认时重校验价格/库存/优惠/归属/过期/状态 | ✅ `revalidate` |
| 条件更新原子状态迁移，一提议只能成功确认一次 | ✅ `claimPending` + 5 路并发实测 |
| 异常不允许先返回成功再补写 | ✅ `markConfirmed` 后置 |
| 测试环境验证重复确认/过期/库存/价格/跨用户/并发/消息重复 | ✅ §6 矩阵（消息重复由原入口购物车指纹幂等覆盖） |
| 原有订单/库存/支付/消息消费者回归通过 | ✅ 19 模块 BUILD SUCCESS |
| 无法实现则保持只读 | —— 本阶段已实现，无需降级 |
