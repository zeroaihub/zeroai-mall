# Agent 能力接入实践手册 —— 自己走一遍 P0 → P6

- **日期**：2026-10-26
- **用途**：拿一个**你自己的业务流程**，按 zeroai-mall 这套「基线 → Spike → 只读工具 → 事件流 → HITL 写」的方法完整做一遍，亲手熟悉 Agent 能力接入的全流程。
- **前置**：已跑通过本项目的 `zeroai-mobile-api`（本地 82 端口），大致知道 `zeroai-ai-starter` 是干什么的。
- **预计耗时**：完整走完（含写操作）约 2–3 个工作日；只走到只读工具约 1 天。

---

## 0. 这份手册怎么用

P0–P6 不是六个功能，而是**一套能力接入的施工顺序**。这份手册把它抽象成可复用的六步，
你换一个业务流程，照样能按这个顺序施工。

| 你要做的 | 对应本手册 |
|---|---|
| 选一个业务流程、写清它 | §2 选题 + §3 流程规格模板 |
| 把流程拆成 Agent 能力 | §4 映射法（读=工具、写=提议、边界=Prompt） |
| 分步施工、每步过门禁 | §5 六阶段动手指南 |
| 照抄骨架、少踩坑 | §6 代码骨架 + §7 常见坑 |
| 跑起来验收 | §8 测试矩阵 + §9 本地环境 |

**贯穿全程的三条铁律**（写不进代码的那部分经验，都在这）：

1. **工具面即权限面**。Agent 能干什么，只取决于注册了哪些工具；不要靠 Prompt 求它别干坏事——Prompt 是引导，工具注册才是权限。
2. **先只读、后写；写也要两段式**。模型可以「准备动作」，但**按确认键的必须是人**。
3. **每阶段都有门禁**。回归测试 + 安全检查不过，就不进下一阶段。宁可慢，不可破。

---

## 1. 全景图：P0–P6 到底在做什么

| 阶段 | 一句话 | 产出 | 门禁（不过不许往下走） |
|---|---|---|---|
| **P0 基线** | 什么都没改时，先把现状量出来 | 基线文档 + 回归测试清单 | 回归能跑、现状有数 |
| **P1 Spike** | 用最小成本验证关键技术假设 | 一个能跑的 Spike 结论 | 核心假设成立或明确证伪 |
| **P2 适配层** | 把 SDK 装进项目，不污染业务模块 | 独立 starter 模块 + 自动装配 | 业务模块零侵入、可整体关闭 |
| **P3 只读工具** | 让 Agent 能查数据，但只能查 | 工具类 + 越权防护 + Prompt | 匿名无用户工具、跨用户零泄漏 |
| **P4 事件流** | 把执行过程变成前端可渲染的契约 | SSE 事件 + 会话隔离 | 断连即停、事件不越权 |
| **P5 能力实验** | Workspace/Skills/Memory 等 Harness 能力摸底 | 实验记录 + 默认开关表 | **能关的默认关**，只留观测 |
| **P6 HITL 写** | 让 Agent 能表达写意图，人来确认 | 提议表 + 确认接口 + 状态机 | 未确认不写库、确认只走原入口 |

> 为什么是这个顺序？因为**每一层都在收窄风险**：先有基线才知道有没有改坏；先 Spike
> 才不会在一个不可用的 SDK 上搭三层楼；先只读才敢让它接触真实数据；写永远放最后，
> 且永远「提议/确认」两段式。

---

## 2. 选题：挑一个合适的练习业务流程

### 2.1 什么样的选题适合

最好的选题是**「查得到 + 写有风险 + 业务里真有」**的流程。纯查询练不到 HITL；纯内部脚本练不到工具设计。

**推荐选题**（按难度递增）：

| 选题 | 读工具 | 写提议 | 为什么适合 |
|---|---|---|---|
| A. 商品评价发布 | `get_product_detail`、`list_my_orders`（确认买过） | `propose_review` | 写操作简单、校验点清晰（必须是本人已收货订单） |
| B. 售后退款申请 | `list_my_orders`、`get_order_detail` | `propose_refund` | 金额敏感、有状态机（可复用 P6 状态机骨架） |
| C. 优惠券申领 | `list_available_coupons` | `claim_coupon` | 轻写 + 库存类约束（限量），练并发裁决 |
| D. 你自己的流程 | 自定 | 自定 | 只要满足下面的自查清单 |

### 2.2 选题自查清单

- [ ] 业务里确实存在这个动作（不是为了练手编的）
- [ ] 有清晰的「查」的面（至少 1–2 个查询接口/服务可复用）
- [ ] 写操作有真实后果（创建单据/改状态/动库存），**必须走人确认**才合理
- [ ] 你能说清楚「什么情况下必须拒绝」（过期/越权/状态不对/超限）
- [ ] 不涉及支付扣款/资金转账（那是另一个量级的风险，不适合练习）

### 2.3 写你的业务流程规格（做任何开发前先写这个）

一页纸就够，用这个模板：

```markdown
# 业务流程：<名称>

## 1. 角色与场景
谁在什么情况下用它？（例：买家收到货后 7 天内想退一件）

## 2. 正常流（Happy Path）
1. 用户说：……
2. 系统查：……
3. 用户确认：……
4. 系统写：……（调用现有哪个服务的哪个方法？必须写明）

## 3. 查询清单（会变成 Agent 工具）
| 用户会问什么 | 背后调哪个服务方法 | 是否涉及用户私有数据 |
|---|---|---|

## 4. 写操作清单（会变成提议/确认）
| 动作 | 谁能做 | 前置校验（全部列出来） | 调用哪个现有写入口 | 失败时怎么回滚 |
|---|---|---|---|---|

## 5. 拒绝场景（必须有可读文案）
| 场景 | 拒绝码 | 给用户看的话 |
|---|---|---|
| 例：申请已超期 | REFUND_WINDOW_CLOSED | 「已超过 7 天售后期，可在订单详情联系客服」 |

## 6. 明确禁止
- Agent 不得……（例：不得替用户选退货理由、不得承诺退款到账时间）
```

> **写入口必须写具体方法名**（如 `IMobileOrderService.asyncSubmit`）。
> P6 的经验：确认接口**只调原入口，一行业务逻辑都不复制**——价格/库存/优惠重校验可以做，重算下单不行。

---

## 3. 业务流程 → Agent 能力映射法

写完规格后，按这张表做拆解。这一步是「产品思考」，比写代码更重要：

| 流程里的元素 | 映射到 | 落在哪 | 关键约束 |
|---|---|---|---|
| 「查 XX」 | **只读工具**（`@Tool(readOnly=true)`） | `ai/mall/tool/` | userId 构造注入，不收参数用户 ID |
| 「办 XX」 | **提议工具**（只写提议表） | `ai/mall/tool/ProposeXxxTool.java` | 绝不直接调写服务 |
| 「用户点头后才办」 | **确认接口** + 状态机 | `ai/mall/proposal/` + `web/ActionProposalController` | 只调原写入口 |
| 「不允许 XX」 | **系统提示词**「绝对禁止」段 | `prompts/xxx-agent-system.st` | 引导用，不是权限 |
| 真正的权限边界 | **工具注册条件** | `MallAgentFactory.buildToolkit` | 匿名/未开启就根本不注册 |
| 「只能看自己的」 | **对象边界**（userId 构造注入） | 每个工具类 | 跨用户查询一律返回「不存在」 |
| 「超时作废」 | 提议 TTL | `ActionProposalProperties` | 过期判定**看时间**不看落库状态 |
| 「不能重复办」 | 幂等键 + 条件 UPDATE | 唯一键 `(user_id, idempotency_key)` + `claimPending` | 数据库裁决抢锁 |

**自查三问**（做完映射后问自己）：
1. 如果 Prompt 失效、模型抽风，我的**工具注册**能不能兜住安全边界？（必须能）
2. 确认接口里有没有**复制**业务逻辑？（必须没有）
3. 并发双击/重复确认/跨用户/过期，这四件事我打算怎么裁决？（见 §6.3 状态机）

---

## 4. 六阶段动手指南

### Stage 0 — 基线与红线（半天）

**目标**：开工前先知道「不改的时候是什么样」。

动手：
1. 把现有回归测试跑绿，记下 `mvn test` 模块数/用例数/耗时。
2. 写下红线清单（放进你的流程规格 §6）：
   - Agent 不能直接调写服务（工具层就没有）
   - 不能编造数据（Prompt + 工具返回必须真实）
   - 不能越权（匿名无用户工具）
3. （可选）如果要量化效果，照 `scripts/agent/baseline/run_baseline.py` 的样子准备 8–12 个测试问题。

**门禁**：回归全绿；红线清单写完。
**常见错误**：跳过基线直接写代码 → 改坏了不知道改坏在哪。

### Stage 1 — Spike：验证关键假设（半天–1 天）

**目标**：用最小成本回答「这事到底能不能成」。

动手（对照你们的选题挑要验证的点）：
1. 模型连通：能不能通过 `OpenAIChatModel` 调通你的模型（`LLM_API_KEY`、`baseUrl`）？
2. 工具循环：最简一个工具（如 `echo` 工具），模型能不能正确调用、拿结果继续推理？
3. 如果做写提议：提议 JSON 往返（模型输出 → 你解析）稳不稳？

产出：一页 Spike 结论（参照 `docs/agent/ADR-001-agentscope-compatibility.md` 的写法）。
**门禁**：核心假设有明确结论（成立/证伪/需换方案）。假设有问题就在这停，不要往下盖楼。

### Stage 2 — 只读工具（1 天）

**目标**：让 Agent 能查数据，而且**只能查**。

动手：
1. 在 `zeroai-ai-starter` 下新建你的工具类（模板见 §6.1）。**所有 AI 代码只放这个模块**。
2. 在 `MallAgentFactory.buildToolkit` 注册。注意三层：
   - 全量只读（谁都能用）
   - 登录用户专属只读（`userId != null` 才注册）
   - 写提议（单独开关，默认关）
3. 写系统提示词的「能力边界」段：**列清楚只有这几个工具**，没列的模型会以为没有。
4. 单测（模板见 §6.4）：`null userId` 必须拒绝、参数校验、降级返回可读错误。

**门禁**：
- [ ] 工具列表里没有任何写服务调用
- [ ] 匿名上下文不注册用户级工具（用单测断言工具集，别靠肉眼）
- [ ] 跨用户查询测试：A 用户拿 B 的 ID 查 → 不存在/空
- [ ] 原有回归全绿

**常见错误**：把 userId 做成工具参数（模型可以传别人的）→ **必须构造注入**。

### Stage 3 — 事件流与会话（1 天）

**目标**：执行过程对前端可见、可渲染，且会话隔离。

动手：
1. 新工具在 `AgentEventMapper` 加进度文案（如「正在查你的订单」）。
2. 事件类型走 `AiEventType` 常量，**不写裸字符串**；新增类型要同步给前端文档。
3. 会话隔离验证：用户 A/B 各自的 sessionId、状态目录互不干扰。
4. 断连测试：客户端断开后请求要停，不许空转烧 token。

**门禁**：事件映射表更新；断连即停；跨用户会话状态不串。
**常见错误**：新增事件类型只改后端 → 前端渲染不出来。

### Stage 4 — 能力实验（可选，半天–1 天）

**目标**：Skills / Memory / Plan Mode 等 Harness 能力，摸底但**默认关闭**。

动手：挑你感兴趣的 1–2 项实验即可（参照 `docs/agent/PHASE-5-harness-capabilities.md`）。
记住 P5 的结论：**只有可观测中间件值得默认开**，其余都是扩大作用面的选项。

**门禁**：实验结论落文档；所有新能力默认 `false`；开关有启动日志快照。

### Stage 5 — HITL 写操作（1–2 天，核心阶段）

**目标**：Agent 能表达写意图，人来确认；确认只走原有业务写入口。

动手顺序（每一步都能独立提交）：

1. **DDL**：提议表 `ai_xxx_proposal`（增量 DDL + 回滚脚本，放 `db-init/`）。
   必备列：`proposal_no`（unique）、`user_id`、`status`、`idempotency_key`（unique with user_id）、
   参数快照、价格摘要、`expire_time`、`fail_code`。索引 `(user_id, status, expire_time)`。
2. **状态机**：`PENDING → CONFIRMING → CONFIRMED | REJECTED`，`PENDING → EXPIRED`（惰性），
   `CONFIRMING → PENDING`（仅系统异常回滚）。**所有迁移走条件 UPDATE**（模板见 §6.3）。
3. **服务**：`createProposal`（幂等）/ `confirm`（抢锁→重校验→调原入口→落状态）/ `get`（跨用户隐藏）。
4. **确认接口**：`POST /your-path/confirm/{proposalNo}`——**唯一能触发写入的入口**，userId 只从登录上下文取。
5. **提议工具**：只 INSERT 提议表，注册条件 = 开关开启 + 已登录。
6. **提示词**：加「绝对禁止」（不得承诺已办）和「关于 XX 提议」（必须告知需确认、过期重新生成）。
7. **测试**（模板见 §6.5）：未确认绝不调写入口、重复确认不双写、并发双击只成一单、过期/越权/状态不对全拒绝。

**门禁**（对照打勾）：
- [ ] Agent 无直接写工具（单测断言工具面）
- [ ] 未确认提议不产生业务数据变化
- [ ] 确认后只调原业务写入口（代码评审确认没有复制逻辑）
- [ ] 重复确认/并发双击不重复产生单据
- [ ] 过期/价格/状态变化/跨用户全部拒绝且文案可读
- [ ] 异常路径没有「先返回成功再补写」
- [ ] 原有回归全绿

**常见错误**：
- 确认接口里「顺便」把业务规则重写一遍 → 以后业务改了这里必坏。**只重校验、只调原入口**。
- 过期判定看落库状态字段 → 并发下判错。**看时间**（`expire_time > now`）。
- `markConfirmed` 写在调写入口**之前** → 崩溃即数据不一致。**写在返回之后**。

---

## 5. 提交节奏（建议）

像 P0–P6 那样，每个阶段一次汇报，格式固定（这也是给自己留的审计痕迹）：

```markdown
## 阶段 X 汇报：<阶段名>
- 变更文件：……
- 测试结果：单测 N 个全绿 / 全量 BUILD SUCCESS
- 安全检查：工具面无越权 / 跨用户零泄漏 / 开关默认关
- 未解决问题：……
- 回滚方式：……（必须能一句话说清怎么退回去）
```

**「回滚方式」能写清楚，才说明你真的知道改了什么。**

---

## 6. 代码骨架速查（直接抄结构）

真实文件都在 `zeroai-ai-starter/src/main/java/com/zeroai/ai/` 下，练习时平行地加你的包。

### 6.1 只读工具模板

```java
package com.zeroai.ai.mall.tool;

import io.agentscope.core.tool.Tool;

/** 只读工具：userId 构造注入，模型无法指定他人身份。 */
public class ListMyRefundsTool {

    private final IRefundService refundService;
    private final Long userId;

    public ListMyRefundsTool(IRefundService refundService, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空：未登录不允许查售后单");
        }
        this.refundService = refundService;
        this.userId = userId;
    }

    @Tool(name = "list_my_refunds",
          description = "查询当前用户自己的售后单。无需参数。返回售后单号、状态、金额。",
          readOnly = true,            // 只读：可并发
          concurrencySafe = true)
    public String listMyRefunds() {
        try {
            // 只调只读服务方法；查询条件必须带 userId
            return ToolJson.toJson(refundService.pageByUser(userId));
        } catch (Exception e) {
            // 降级要显式，不要返回空数组冒充「没有售后单」
            return "{\"error\":\"REFUND_QUERY_UNAVAILABLE\",\"hint\":\"售后查询暂时不可用\"}";
        }
    }
}
```

要点：
- `readOnly = true` / `concurrencySafe = true` 只读才这么标；提议工具要 `readOnly = false, concurrencySafe = false`。
- 返回 JSON 字符串，含 `error` 字段时 Prompt 会引导模型转成自然语言。
- 参数校验失败返回 `{"error":..., "hint":...}`，不要抛栈给模型看。

### 6.2 工厂注册模板（对照 `MallAgentFactory.buildToolkit`）

```java
Toolkit buildToolkit(MallAgentContext context, Consumer<ProposalVO> listener) {
    Toolkit toolkit = new Toolkit();
    toolkit.registerTool(new SearchProductsTool(searchPort));          // 全量只读
    if (context != null && context.userId() != null) {
        Long userId = context.userId();
        toolkit.registerTool(new ListMyRefundsTool(refundService, userId)); // 登录专属
        if (proposalService != null && actionProperties.isEnabled()) {
            toolkit.registerTool(new ProposeRefundTool(proposalService, userId, listener));
        }
    }
    return toolkit;   // 匿名 = 只有全量只读，数据边界在对象层就成立
}
```

### 6.3 状态机 / 条件 UPDATE 模板（P6 的灵魂）

```java
@Update("UPDATE ai_xxx_proposal SET status = 'CONFIRMING', update_time = #{now} "
      + "WHERE proposal_no = #{no} AND user_id = #{userId} "
      + "AND status = 'PENDING' AND expire_time > #{now}")
int claimPending(@Param("no") String no, @Param("userId") Long userId, @Param("now") LocalDateTime now);
// 返回 0 = 没抢到（已确认/已过期/别人的/并发已被抢）→ 用状态+时间解释原因，不要重试覆盖
```

`confirm` 服务的骨架顺序（顺序错了就违反「不先成功后补写」）：

```
1. claimPending 抢锁（0 行 → explain 原因并返回失败）
2. revalidate  重校验（归属/过期/参数/库存/价格……全部按当前库里的真实状态）
3. callOrigin  调原业务写入口（只此一处！）
4. markConfirmed 落状态 + 业务单号   ← 必须在 3 成功返回之后
   业务拒绝 → markRejected(code)；系统异常 → revertToPending(可重试)
```

### 6.4 工具单测模板（对照 `MallToolTest`）

```java
@Test
void refundToolMustRejectNullUser() {
    assertThrows(IllegalArgumentException.class, () -> new ListMyRefundsTool(svc, null));
}

@Test
void refundToolShouldDegradeWhenServiceFails() {
    IRefundService broken = mock(IRefundService.class);
    when(broken.pageByUser(any())).thenThrow(new RuntimeException("db down"));
    String r = new ListMyRefundsTool(broken, 1L).listMyRefunds();
    assertTrue(r.contains("error"));
    assertFalse(r.contains("db down"), "内部异常不得泄漏给模型");
}
```

### 6.5 提议服务单测必测清单（对照 `ActionProposalServiceTest`）

| # | 用例 | 断言 |
|---|---|---|
| 1 | 创建提议 | **写入口零调用**（verifyNoInteractions） |
| 2 | 同快照再提 | 复用同一 proposalNo，不插第二行 |
| 3 | 重复确认 | 只调一次写入口；返回已确认 + 原单号 |
| 4 | 并发双击 | 只成一单（其余返回抢锁失败码） |
| 5 | 过期确认 | 拒绝 EXPIRED |
| 6 | 跨用户确认/查询 | 不存在（防探测） |
| 7 | 校验失败（价格/状态/库存…） | 拒绝 + 对应错误码 |
| 8 | 系统异常 | 回滚可重试，**不落业务单号** |
| 9 | 视图字段 | 不暴露主键/幂等键/敏感信息 |

---

## 7. 常见坑清单（全部实际踩过）

| # | 坑 | 现象 | 解法 |
|---|---|---|---|
| 1 | 环境变量名 Spring 不认 | `AI_ACTION_ENABLED=true` 无效，日志仍 false | `application.yml` 显式映射：`enabled: ${AI_ACTION_ENABLED:false}` |
| 2 | Docker MySQL 时钟是 UTC | SQL `NOW()` 造的测试数据被 Java 判为已过期 | 代码全用 Java 时间戳比较；造数用 `DATE_ADD(NOW(), INTERVAL 10 HOUR)` |
| 3 | 表里 NOT NULL 无默认值 | MQ/插入报 `Field 'specifications' doesn't have a default value` | 造数把 NOT NULL 列补全 |
| 4 | Mockito 重载歧义 | `any()` 不知匹配 `insert(T)` 还是 `insert(List)` | `ArgumentMatchers.<T>any()` 显式指定类型 |
| 5 | 裸值与匹配器混用 | `InvalidUseOfMatchers` | 全部用 `eq()` 包裹 |
| 6 | 过期看落库状态 | 并发下误判 CONFIRMING | 过期判定看 `expire_time` 时间 |
| 7 | 异常文案泄漏内部信息 | 用户看到 `db down` | 仅业务异常回显；系统异常给固定「请稍后重试」 |
| 8 | 软删数据当有效 | 查车返回 0 件误以为 bug | 看 `del_flag`；造数用有活跃数据的用户 |
| 9 | 第一次请求慢 | 建目录/建连接导致超时误判 | 预热一次，或提前建目录 |
| 10 | Bash 管道吞退出码 | `mysql ... 2>/dev/null \| grep` 静默失败 | 查 stderr；别用 grep 过滤错误 |

---

## 8. 验收：怎么算「练完了」

### 8.1 交卷清单

- [ ] 一页业务流程规格（§2.3 模板填完，写入口是真实方法名）
- [ ] 映射表：查询→工具、写→提议、拒绝场景→错误码（§3）
- [ ] 只读工具 + 单测全绿
- [ ] （若做写）提议表 DDL + 回滚脚本 + 状态机服务 + 确认接口
- [ ] 实测矩阵（仿照下表）跑完
- [ ] 每阶段汇报记录（含回滚方式）
- [ ] 系统提示词：能力边界 + 绝对禁止 + 业务特有约束

### 8.2 实测矩阵模板

| 场景 | 期望 | 实测 |
|---|---|---|
| Agent 生成提议后不产生业务数据 | 业务表数量不变 | |
| 用户确认后走原入口 | 产生且仅产生一条单据 | |
| 重复确认 | 拒绝 + 原单号 | |
| 并发双击（≥3 路） | 只成一单 | |
| 过期 | 拒绝 | |
| 越权/跨用户 | 拒绝且不暴露存在性 | |
| 状态/金额变化 | 拒绝 | |
| 关闭开关 | 工具消失、接口 404 | |
| 原有回归 | 全绿 | |

### 8.3 自我拷问（最后 5 分钟）

1. 我能不能**一句话**说清「Agent 绝对做不了什么」？（应该能：工具面决定）
2. 业务规则改了，我要改几处？（理想答案：**原服务一处**，确认接口不用动）
3. 回滚怎么做？（理想答案：关开关 + 执行 rollback SQL，两句话说完）

---

## 9. 本地环境速查（练习时要用）

| 组件 | 地址 | 账号/密码 |
|---|---|---|
| MySQL（容器 `zeroai-mysql`） | `localhost:3307` | `root` / `@@123456Java`，库 `zeroai_mall` |
| Redis | `localhost:6380` | 无密码 |
| RabbitMQ | `localhost:5673`（管理台 15673） | — |
| mobile-api | `82` | 登录拿 token 后放 `Authorization` 头 |

启动（容器）：
```bash
docker compose -f docker-compose-es7-rabbitmq-redis-mysql.yml -f docker-compose.local.yml up -d
bash scripts/dev/init-db-local.sh
```

IDEA 数据源注意两点：
1. **端口是 3307 不是 3306**（3306 是宿主机上可能存在的另一个 MySQL，连它会报
   `Access denied for user 'root'@'localhost'`——那是连错库了）。
2. 密码含 `@`，URL 里注意转义或用 IDEA 连接面板单独填。

启动 mobile-api（或 IDEA 里直接跑 Application，环境变量照抄）：
```bash
export JAVA_HOME=$HOME/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home
AI_ENABLED=true AI_ENGINE=agentscope AI_ACTION_ENABLED=true \
AI_SKILLS_ENABLED=false AI_MEMORY_ENABLED=false AI_OBSERVABILITY_ENABLED=true \
LLM_MODEL=deepseek-flash LLM_API_KEY=<你的key> \
java -jar jars/zeroai-mobile-api.jar --server.port=82
```

（`AI_ACTION_ENABLED` 要生效，`application.yml` 里必须有 `${AI_ACTION_ENABLED:false}` 显式映射——参考现有 `zeroai.ai.action` 配置块。）

---

## 10. 延伸阅读（本仓库现成材料）

| 文档 | 你练到哪一步时看 |
|---|---|
| `docs/agent/BASELINE.md` | Stage 0：基线怎么做 |
| `docs/agent/ADR-001-agentscope-compatibility.md` | Stage 1：Spike/ADR 怎么写 |
| `docs/agent/PHASE-2-3-agent-tools.md` | Stage 2：工具与越权防护 |
| `docs/agent/PHASE-4-event-stream.md` | Stage 3：事件与会话 |
| `docs/agent/PHASE-5-harness-capabilities.md` | Stage 4：能力实验与默认开关 |
| `docs/agent/PHASE-6-hitl.md` | Stage 5：HITL 全设计与实测矩阵 |
| `docs/agent/BASELINE-METRICS.md` | 想量化效果时 |

---

**最后一句话**：这套流程的价值不在「接了个 SDK」，而在于**每一步都把风险面钉死**——
工具面钉死权限，状态机钉死并发，确认接口钉死业务唯一入口，门禁钉死回归。
换任何业务流程，这四根钉子都不要省。
