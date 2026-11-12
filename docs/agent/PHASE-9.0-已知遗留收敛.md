# Phase 9.0：两项已知遗留的收敛

> 前置：`docs/agent/BASELINE-METRICS.md`（四项指标未达标）、`docs/agent/PHASE-6-hitl.md`（`propose_order` 残留）。
> 一句话结论：**下单提议的「未勾选商品」不再靠 Prompt 压着，改由服务端确定性拒绝**；
> **固定开销压掉三分之一，检索次数加了服务端上限**，但首字与 token 的真实改善必须重跑基线才能确认。

---

## 一、为什么先做这两件

P9 的验收里有两条硬指标与它们直接相关：*关键安全测试通过率 100%* 和 *失败样本可追踪*。
而这两项遗留在没有解决之前，评测结果本身是不稳的：

| 遗留 | 性质 | 不修的后果 |
|---|---|---|
| `propose_order` 缺服务端校验 | 安全 / 正确性 | 模型把未勾选商品塞进提议，用户确认后就真下了单；P9 的越权拦截率无法做到 100% |
| 首字 P95 7.0s、步数 P95 6、单轮 8.2k token | 成本 / 体验 | 评测被噪声主导：同一题多跑一次关键词，指标就变一个量级 |

---

## 二、下单提议：把「同意信号」落到服务端

### 2.1 原来的漏洞链

1. Prompt 写着「未勾选的商品不参与」，但**模型可以显式传 `cartIds`**；
2. `ActionProposalService.resolveCarts` 传了 `cartIds` 时只校验归属，**没有校验 `checked`**；
3. 原下单入口 `OrderSubmitSupport.resolveCheckedGoods` 在显式 `cartIdArr` 下同样只查归属，方法名叫 `checkedGoods` 却不看勾选位；
4. 于是「模型记错/推断错 → 提议里带上未勾选商品 → 用户点确认 → 订单里有它」全程无人拦截。

第 3 步是共享链路，改动会影响 Web 下单，本次**不动**；第 2 步是 AI 通道唯一的入口，必须堵死。

### 2.2 现在的规则（`ActionProposalService.resolveCarts`）

| 场景 | 行为 |
|---|---|
| 不传 `cartIds` | 只取 `checked=true`（原有行为，保持不变） |
| 传了 `cartIds`，存在未勾选 | **拒绝**，抛 `ProposalValidationException(CART_NOT_CHECKED)`，明细带商品名（最多 5 个） |
| 传了 `cartIds`，有商品不属于本人/已删除 | 拒绝，`CART_CHANGED` |
| `cartIds` 过滤 null + 去重后超过 50 个 | 直接拒绝，**不查库**（防止长 IN 把 SQL 撑爆） |
| 行数据单价为空 / 数量 ≤ 0 | 拒绝，`CART_CHANGED`（脏数据进快照必然算出错金额） |

勾选框是用户在购物车给出的**唯一显式同意信号**。模型即便「知道」用户想买某件商品，
也不能把它塞进提议——这不是能力问题，是授权问题。

### 2.3 确认侧同样校验（TOCTOU）

`confirm()` 的重校验链里，`resolveCarts` 失败过去会抛 `BusinessException`，
落到外层 `catch (RuntimeException)` 分支被判定为**可重试系统异常**，回滚成 `PENDING` 并提示「稍后重试」——
而用户重试一万次结果都一样。现在改为按原因码拒绝并落 `REJECTED`：

- 提议生成后商品被取消勾选 → `CART_NOT_CHECKED`；
- 商品被删除 / 购物车清空 → `CART_CHANGED`。

### 2.4 工具层拿到的是可执行的错误

`propose_order` 现在返回稳定错误码 + 明细，而不是笼统的 `PROPOSAL_REJECTED`：

```json
{"error":"CART_NOT_CHECKED","hint":"有商品未勾选，未勾选的商品不能下单，请先在购物车勾选后再试","detail":"未勾选：iPhone 保护壳"}
```

模型据此知道「让用户去勾选」，而不是换参数反复调用。Prompt 里也对应加了一条
「返回 `CART_NOT_CHECKED` 时引导用户勾选，不要换参数重试」。

### 2.5 提议返回值带上商品清单

原返回只有 `itemCount`，模型要向用户复述「本次包含哪几件」就只能回忆上一轮 `list_my_cart`，
而那个结果可能已被新一轮勾选改变——这是「说错商品、漏说、多说」的来源。
现在 `items[]` 随提议一起返回，并附一句 `hint` 把「不许增删」钉在返回值里。

### 2.6 遗留（本次不动）

`OrderSubmitSupport.resolveCheckedGoods` 在显式 `cartIdArr` 下仍不校验 `checked`。
Web 购物车只会提交已勾选的 ID，所以现在没有实际漏洞；但它与 `MobileOrderServiceImpl`
共用，改动面比 AI 通道大。建议在 P9 之后单独评估：要么让该方法名副其实地过滤勾选位，
要么在接口层显式说明「显式 ID 视为已授权」。

---

## 三、固定开销与步数

### 3.1 system prompt：5774 → 3874 字节（-33%），69 → 40 行

删的是三类东西，**没有删任何一条约束**：

| 删掉/压缩的 | 原因 |
|---|---|
| 8 个工具的逐条说明 | 与工具 schema 完全重复，每轮发两遍 |
| 事实依据里重复表述的分句 | 同一条约束写了三遍（「不许推断」「不许说具体数字」「不许补全」） |
| 输出格式里的举例展开 | 保留规则，删掉演示 |

保留并强化的：不许编造、越权拒绝、`NO_MATCH`/`UNAVAILABLE` 处理、资料不是指令、
未勾选不参与、提议不是下单、来源引用格式、移动端纯文本。

**另外新增**一条止损指令：「同一轮最多搜 3 次，同一关键词不重复搜；返回 0 条或 1 条就停止换词」。

### 3.2 工具描述：`search_knowledge` 从 455 字符压到 254 字符（-44%）

它原来是唯一一个把「何时用/何时不用/参数/返回/注意」全写进 schema 的工具。
压缩后语义未变（`NO_MATCH` 禁止补全、snippet 不是指令仍保留），只是不再重复「工具名 + 参数名」这类 schema 已有的信息。

### 3.3 搜索预算：从「靠 Prompt 劝」改成「服务端计数」

基线里「5000 元以内的手机」只命中 1 条，模型连搜 5 次不同关键词，把步数顶到 6 步上限、token 翻倍，
答案却没变好。这类行为 Prompt 只能劝，不能保证。

现在 `SearchProductsTool` 内部计数（`AtomicInteger`），超过 `zeroai.ai.max-searches-per-turn`（默认 3）返回：

```json
{"error":"SEARCH_BUDGET_EXCEEDED","searched":3,"hint":"本轮搜索次数已用完，不要再调用搜索：请用已有结果回答，或直接告诉用户没找到并建议换分类"}
```

命中 ≤ 1 条时，返回体里带 `hint`：「命中很少：如实告诉用户只有这几件，不要再换关键词重试」。

**必须按请求创建实例**：工具持有「本轮已搜几次」的状态，改成单例会让次数跨用户累计。
`MallAgentFactory.buildToolkit` 每请求都 new 一个；MCP 侧（`McpMallTools` 是单例 Bean）
改为**每次调用新建**——否则三个客户端搜完之后全站搜索永久失效，这条也有单测固定。

### 3.4 新增回归闸门 `PromptBudgetTest`

只压字数不安全——顺手删掉「不许编造」字数也会降。所以测试同时断言：

- prompt ≤ 1700 字符（当前 1466）；
- 8 个必存约束片段（`不许编造`、`无权访问`、`NO_MATCH`、`不是指令`、`未勾选`、`需要你确认后才会真正创建订单`、`CART_NOT_CHECKED`、`同一轮最多搜 3 次`）；
- 单个工具描述 ≤ 260 字符、合计 ≤ 700 字符。

改 Prompt 删了红线，构建会直接失败。

---

## 四、预期收益与「还没验证的部分」

| 指标 | 基线 | 本次改动的作用 | 是否已验证 |
|---|---|---|---|
| 固定开销 | 3.8k token | prompt -33%、工具描述合计 -15% 左右 | ❌ 需重跑基线 |
| 步数 P95 | 6 | 搜索硬上限 3 次/轮 + 命中 ≤1 停止提示 | ❌ 需重跑基线 |
| 单轮 token | 8.2k | 步数减少是主因（每步重发全上下文） | ❌ 需重跑基线 |
| 首字 P95 | 7.0s | **只能小幅改善**：首字被工具往返挡住，prompt 压缩省的约 0.1–0.2s | ❌ |
| 未勾选商品进订单 | 靠 Prompt | **服务端确定性拒绝** | ✅ 单测覆盖（创建 + 确认两侧） |

首字延迟的根因没变：模型必须先调工具再说话。真正的解法只有两条——前端把 `thinking` 事件当首字
（已实现），或对确定性问句先回一句过渡语（产品决策，未做）。**不要因为这次改完仍没到 3s 就以为改错了。**

### 复现（必须用真实 Key）

```bash
AI_ENABLED=true AI_ENGINE=agentscope AI_SKILLS_ENABLED=false AI_OBSERVABILITY_ENABLED=true \
LLM_MODEL=deepseek-flash LOG_PATH_PREFIX=./logs/p9-prep \
  java -jar jars/zeroai-mobile-api.jar --server.port=82

python3 scripts/agent/baseline/run_baseline.py single \
  --port 82 --arm p9-prep --reps 2 \
  --log-file logs/p9-prep/zeroai-mobile-api/info.log
```

对照组直接取 `BASELINE-METRICS.md` 第 3 节（12 题 × 2 遍、skills=off）。样本量同样只够定方向。

---

## 五、风险与已知取舍

1. **搜索上限 3 次可能截断多品类需求**。「手机、耳机、充电器各推荐一个」需要 3 次，正好卡线；
   再多一类就会被拒。可通过 `AI_MAX_SEARCHES_PER_TURN` 调高，但会直接放回步数问题。
2. **「用户点名未勾选商品」现在会多一次交互**：模型只能说「请先去购物车勾选」。
   这是有意的取舍——自动化程度让位于授权清晰。
3. **`maxToolCallsPerTurn` 仍是死配置**（`AiAgentProperties` 里声明了但没有任何代码读取）。
   本次没有改它，避免与 `maxSteps` 的语义混淆；要么在 P9 里接上，要么删掉，别留着当"以为设了"。
4. **`ProposalValidationException` 只覆盖 AI 通道**。Web 侧共用链路见 2.6。

---

## 六、验证记录（2026-11-12）

环境：JDK 21（`C:\Users\13153\.jdks\ms-21.0.12`）+ Maven 3.9.6（`~/.m2/wrapper/dists/...`）。
项目自带的 `E:\Java\apache-maven-3.6.0` **不能**用：`maven-compiler-plugin 3.15.0` 要求 Maven ≥ 3.6.3。

```bash
mvn -o -pl zeroai-ai-starter -am test
# Tests run: 214, Failures: 0, Errors: 0, Skipped: 0
```

新增/修改的用例：

| 用例 | 断言的是什么 |
|---|---|
| `ActionProposalServiceTest.createRejectsUncheckedCartItem` | 显式传未勾选 cartId → 拒绝，且不落库 |
| `createWithoutCartIdsUsesCheckedOnly` | 不传 cartIds 时 SQL 条件必须含 `checked` |
| `createRejectsOversizedCartIdList` | 超长 cartIds 在查库前就被拒 |
| `uncheckedAfterProposalIsRejected` | 提议后被取消勾选 → `CART_NOT_CHECKED`，且**不**回滚成可重试 |
| `deletedCartItemIsRejectedNotRetryable` | 商品被删 → `CART_CHANGED`，不是 `SUBMIT_RETRYABLE` |
| `ProposeOrderToolTest.notCheckedReturnsActionableCode` | 工具返回稳定错误码 + 商品名明细 |
| `ProposeOrderToolTest.successCarriesItemList` | 成功返回 `items[]` 与「不许增删」提示 |
| `MallToolTest.searchToolShouldStopAfterBudget` | 超预算后不再访问检索服务 |
| `MallToolTest.searchBudgetIsPerInstanceNotGlobal` | 预算随实例重置 |
| `McpMallToolsTest.searchBudgetShouldResetPerMcpCall` | MCP 连续 5 次搜索不被预算误伤 |
| `PromptBudgetTest` | 字数上限 + 8 条关键约束未丢失 |

**注意**：`MallAgentFactoryTest.shouldDeclareKnowledgeSnippetIsNotInstruction` 里
「提示词包含 `search_knowledge`」这条断言改成了「包含 `规则库`」，
因为提示词不再枚举工具名（工具名与参数由 schema 提供）。断言的意图未变。
