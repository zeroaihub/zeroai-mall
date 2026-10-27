# Phase 6.2：对话历史落 ES、环境变量映射补全、下单提议启用

> 承接 `PHASE-6-hitl.md`（提议链路）与 `PHASE-6.1-prompt-guard-and-h5-entry.md`（提示词约束 + H5 入口）。
> 本阶段把 AI 导购从「能聊」推到「有记忆、能发起下单提议」，并把三个遗留配置问题一次清掉。

## 一、修掉的三个遗留问题

| 问题 | 现象 | 处理 |
|---|---|---|
| `.env` 里的 `AI_ENGINE` 不生效 | 必须额外 `export ZEROAI_AI_ENGINE=agentscope`，否则启动日志仍是 spring-ai | `application.yml` 显式加 `engine: ${AI_ENGINE:spring-ai}`。Spring 的 relaxed binding 只认 `ZEROAI_AI_ENGINE`，`.env` 里那种平铺写法必须手工映射 |
| `AI_ACTION_ENABLED` 没有映射 | 提议链路永远起不来，`ai_action_proposal` 表也没人建 | 补 `zeroai.ai.action.*` 映射；同时执行 `db-init/ai_action_proposal.sql` |
| `logs/` 未忽略 | 启动日志、E2E 截图、临时脚本全在 `git status` 里 | `.gitignore` 加 `logs/` |

配置映射（`zeroai-mobile-api/src/main/resources/application.yml`）：

```yaml
zeroai:
  ai:
    enabled: ${AI_ENABLED:false}
    engine: ${AI_ENGINE:spring-ai}          # 新增
    action:                                  # 新增
      enabled: ${AI_ACTION_ENABLED:false}
      proposal-ttl-seconds: ${AI_ACTION_TTL_SECONDS:600}
      max-items-per-proposal: ${AI_ACTION_MAX_ITEMS:10}
    history:                                 # 新增
      enabled: ${AI_HISTORY_ENABLED:true}
      index: ${AI_HISTORY_INDEX:zeroai_ai_chat}
      max-answer-chars: 8000
      default-page-size: 20
      max-page-size: 50
```

启动日志可验证：

```
写操作提议链路已启用 ttlSeconds=600 maxItems=10（Agent 仍不能直接下单，必须用户确认）
Agent 通道配置 engine=agentscope model=deepseek-flash ... actionEnabled=true
```

## 二、对话历史落 ES

### 分层：starter 只定义 SPI，入口模块给实现

```
zeroai-ai-starter
  com.zeroai.ai.history.AiChatExchange        存档对象（一轮问答）
  com.zeroai.ai.history.AiChatHistoryStore    SPI: save / recent / clear
  com.zeroai.ai.history.AiChatHistory         门面：聚合一轮 + 异步落库 + 空操作降级
  com.zeroai.ai.config.AiHistoryProperties    zeroai.ai.history.*
  com.zeroai.ai.config.AiHistoryAutoConfiguration
  com.zeroai.ai.web.AiHistoryController       GET/DELETE /ai/history

zeroai-mobile-api
  com.zeroai.mobile.api.service.ai.EsAiChatHistoryStore   ES 实现
```

为什么不在 starter 里直接写 ES：starter 的定位是「可复用的 AI 能力包」，
一旦依赖 `RestHighLevelClient` 就等于把整个技术栈钉死。ES 只认一个 43 行的 SPI。

### 生命周期约定

- **只在 done / error 落库**：中途断连不写，避免把半句话当完整回答存下来。
- **匿名不存档**：`userId == null` 直接丢弃，匿名的 sessionId 每次随机，存了也查不回来。
- **空回答不存档**：首字就被中断的空气泡不产生历史条目。
- **答案截断**：超过 `max-answer-chars` 按前缀截断，不整条丢弃。
- **异步 + 吞异常**：落库走 `Schedulers.boundedElastic()`，写失败只 warn。
  历史是旁路能力，ES 挂了不能影响对话。
- **两条通道都记**：Agent 通道与 spring-ai 单轮通道都会记录，切引擎不丢历史。

### 索引

首次写入自动建索引，显式 mapping（`userId: long`、`createdAt: date`、`tools: keyword`），
1 分片 0 副本——单节点开发环境多副本只会一直 yellow。写入用
`RefreshPolicy.IMMEDIATE`，答完刷新页面立刻能看到。

### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/ai/history?limit=20` | 当前用户最近记录，倒序；`limit` 按 `max-page-size` 封顶 |
| DELETE | `/ai/history` | 清空当前用户全部记录，返回删除条数 |

userId 只从 `AiUserContext` 取，不接受参数，因此不存在「换个 ID 看别人聊了什么」的入口。

## 三、下单提议真正可用

### 关键修复：幂等键不能复用「已结束」的提议

原实现是 `selectByIdempotencyKey -> 命中即返回`。启用后立刻暴露问题：
用户确认过一条提议，再对同一购物车说「帮我下单」，拿到的是一条 `CONFIRMED` 的旧提议，
前端卡片看起来还能点，一点就报「该提议已确认过」。

现在的语义是**「同一快照只存在一个待确认提议」**，而不是「同一快照永远只有一条」：

```
for attempt in 0..9:
    key = sha256(user : address : coupon : items [: #attempt])
    existing = select(user, key)
    if existing == null      -> insert, return
    if existing is PENDING   -> return existing      # 真正的幂等
    else                     -> continue             # 换下一个 key，保留历史记录
```

`attempt=0` 不加后缀，与既有数据兼容；唯一约束 `(user_id, idempotency_key)` 仍然生效。

### 提示词：不要重复反问，不要传 cartIds

启用提议后发现 deepseek-flash 有两个坏习惯，都在 `mall-agent-system.st` 里加了硬约束：

1. 用户说了「下单」还反问「确认吗」——提议卡片本身就是确认入口，重复反问只是让用户多点一次。
2. 把上一轮记住的 `cartIds` 当成本轮事实传进来，导致把已取消勾选的商品也算进金额。
   现在要求默认不传 `cartIds`，让服务端按当前已勾选商品计算。

> 残留：第 2 条在 deepseek-flash 上仍偶发（本次验证中就出现过一次把未勾选商品计入）。
> 提示词能压住大部分情况，但模型不保证。要彻底解决得在 `ProposeOrderTool` 里做服务端校验，
> 属于独立改动，未在本阶段展开。

## 四、H5 侧

| 文件 | 变更 |
|---|---|
| `src/api/ai.js` | 新增 `fetchAiHistory` / `clearAiHistory` / `confirmOrderProposal` |
| `src/views/ai/index.vue` | 进页拉历史并还原成气泡；`confirm_action` 渲染提议卡片；卡片内「确认下单」调用独立接口；清空改为「确认后同时删服务端历史」 |

提议卡片刻意与普通气泡区分（蓝边框 + 金额红字 + 底部提示「只有你点确认才会创建订单」），
状态机：`pending -> confirming -> confirmed / failed`，失败只显示原因，不自动重试。

## 五、验证

### 接口级

```
GET  /ai/history            -> {"code":200,"data":[]}                 # 初始
对话一轮                     -> ES zeroai_ai_chat docs=1，question/answer/tools/steps 全对
GET  /ai/history            -> 返回该条记录
「帮我把购物车里的商品下单」 -> thinking×4 / tool_result×4 / confirm_action×1 / done
ai_action_proposal          -> PENDING, actual_price=898.00, cart_ids=17,18
POST /ai/action/confirm/xx  -> ok=true, orderSn=1793070087000003
ai_action_proposal          -> CONFIRMED + order_sn
local_message               -> id=17 ORDER 1793070087000003（消费者未启动，订单仍走 MQ 异步落库）
DELETE /ai/history          -> removed=6，ES count 归零
清空后再聊一轮               -> ES count=1（写入链路仍然正常）
```

### 浏览器端（Chrome CDP，`logs/verify/ai_e2e_proposal.js`）

```
历史气泡数 : 5          # 重进页面从 ES 还原
提议卡片   : 待确认的下单提议 | ￥898.00 | 2 件商品 | 有效期至 10-27 11:21 | 确认下单
确认结果   : 已提交，订单号 1793070666000005
```

截图：`logs/verify/ai-proposal.png`（待确认）、`ai-proposal-confirmed.png`（已提交）、
`ai-proposal-history.png`（历史还原）。

### 单元测试

`AiChatHistoryTest` 覆盖：存档内容完整、工具名去重、匿名空操作、无存储空操作、
超长截断、重复 `success()` 只落一条、空回答不存档。

## 六、验证用的测试数据（dev 库）

- `ai_action_proposal` 表：`db-init/ai_action_proposal.sql` 建好（此前从未执行过）。
- 用户 93 原本没有有效购物车（`del_flag=1`），为验证提议补了两行（id=17/18，¥699 + ¥199），
  地址沿用已有的 id=3 默认地址。这些是 dev 数据，需要干净环境时删掉即可。

## 七、仍未做

- `zeroai-message-consumer` 没启动：确认提议后订单只进了 `local_message` outbox，
  `shop_order` 里看不到新订单。这是原有异步架构，不是本阶段引入的。
- 清空历史只清 ES 记录，不清 AgentScope 的会话状态（`sessionId` 仍是 `u93`），
  所以模型仍然记得上下文。要真正「忘掉」需要给会话加轮换标识，属于独立改动。
