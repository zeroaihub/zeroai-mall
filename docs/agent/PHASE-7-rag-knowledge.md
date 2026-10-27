# Phase 7：RAG 与知识检索

> 上游方案：`docs/zeroai-mall-AgentScope-Workbuddy-实施方案.md` §Phase 7（可选扩展）。
> 承接 `PHASE-6.2-history-es-and-action-enable.md`。
> 一句话结论：**知识库做了，向量库没做**——用现有 ES 做检索足够，先拿数据再决定要不要加中间件。

## 一、问题：政策类问题没人「负责」

Phase 6 之后 Agent 已经能查商品、查订单、发起下单提议，但「退款多久到账」「运费怎么算」这类问题没有数据源：

- 没有 `policy` 表，也没有帮助中心接口；
- 让模型凭通用常识回答，等于让它在售后和价格规则上**编造**——这是交易系统里最不能接受的错误类型；
- 把政策写进 System Prompt 也不行：模型会记混、会随对话漂移，且每次改政策要重训提示词。

所以 Phase 7 要解决的是：**规则类问题有明确事实来源，且回答能指出依据**。

## 二、不引入 PGVector：一个被数据推翻的预设

方案原文写的是「只有确有语义检索需求时才引入 PGVector」。实测数据表明，当前并不需要：

| 观察 | 数据 |
|---|---|
| 语料规模 | 6 篇文档、约 4.5k 字，任何向量库的召回优势在小语料上都难以体现 |
| 提问方式 | 集中在固定业务词（退款、运费、优惠券、订单状态），BM25 + ik 分词召回良好 |
| 引入成本 | 新增一个中间件 + embedding 调用 + 一条失败链路 + 一份部署 profile |
| 替代成本 | 复用已有 ES：0 个新容器、0 个新依赖 |

于是 `.env.example` 里的 `PGVECTOR_*` 被**注释掉**并写明原因，路线图里把「语义召回」降级为 P10，
并规定触发条件：**先用评测集证明 BM25 不够**，再谈向量。真到那天，只需要换掉
`KnowledgeSearchPort` 的实现，工具、提示词、前端一行不用改。

## 三、知识源：代码即知识库

文档放在 `zeroai-ai-starter/src/main/resources/knowledge/*.md`，随 jar 打包：

| sourceId | 标题 | 覆盖内容 | 事实来源 |
|---|---|---|---|
| `kb-after-sale-refund` | 退款与售后政策 | 退款入口、整单退款、状态流转、到账口径、无换货 | `OrderRefundController`、`OrderLifecycleSupport.refund`、`AdminOrderRefundSupport` |
| `kb-shipping-freight` | 配送与运费规则 | 运费计算、包邮门槛、无物流轨迹、发货与取消 | `OrderSubmitSupport` 运费分支、`unpaidOrderCancelDelayTime` |
| `kb-order-payment` | 下单与支付规则 | 下单流程、金额口径、超时取消、订单状态机、支付回调 | `OrderSubmitSupport`、`OrderStatusEnum`、`PayController` |
| `kb-coupon-rules` | 优惠券使用规则 | 门槛口径（含运费）、不可用原因、不可跨账号 | `OrderValidationSupport.validateCoupon` |
| `kb-shopping-help` | 购物流程与常见问题 | 搜索与筛选、规格与库存、购物车勾选、订单入口 | H5 路由与 `FilterBar` / 订单页按钮 |
| `kb-ai-assistant` | AI 助手能力边界 | 能做什么、不能做什么、隐私处理方式 | 工具面与脱敏实现 |

三条硬规则：

1. **只写能在代码里找到依据的事实**。写不出依据的（退款到账天数、赔付标准、发票规则）就**不写**，让检索返回「查不到」。
2. **不写商品实时数据**。价格、库存、销量走商品工具，知识库只讲规则。
3. **不写用户私有数据**。订单、地址、购物车走各自的 `list_my_*` 工具，知识库连字段都没有。

frontmatter 承载验收要求的元信息：

```yaml
---
sourceId: kb-after-sale-refund   # 稳定标识，同时是 ES 文档 ID
title: 退款与售后政策             # 回答里给用户看的引用名
category: after-sale
version: 1                       # 内容变更递增，便于排查口径
updatedAt: 2026-10-27            # 必填，缺失即拒收——无法判断是否过期
visibility: public               # public 才进公共索引；internal 预留运营文档
tags: 退款,售后,退货,换货,退款到账   # 人工维护的「用户会怎么说」同义词
---
```

`KnowledgeCatalog` 加载时的处理：缺 `sourceId / title / updatedAt / 正文` 一律拒收；
`sourceId` 重复只保留先出现的；命中注入特征整篇拒收并记 ERROR；单篇失败不影响其余文档加载。

## 四、分层：starter 定义能力，入口模块实现存储

```
zeroai-ai-starter
  com.zeroai.ai.knowledge
    KnowledgeDocument / KnowledgeVisibility / KnowledgeHit   数据载体
    KnowledgeCatalog           Markdown + frontmatter 解析（纯函数，可单测）
    KnowledgeGuard             注入检测与片段清洗
    KnowledgeSearchPort        SPI：search(query, limit)
    KnowledgeService           门面：状态机 + 去重 + 清洗 + 截断
    KnowledgeProperties        zeroai.ai.knowledge.*
  com.zeroai.ai.config.KnowledgeAutoConfiguration
  com.zeroai.ai.mall.tool.SearchKnowledgeTool   只读工具 search_knowledge
  com.zeroai.ai.web.KnowledgeController         GET /ai/knowledge/search（评测与排障）

zeroai-mobile-api
  com.zeroai.mobile.api.service.ai.EsKnowledgeStore   ES 实现 + 启动同步（ApplicationRunner）
```

与对话历史同构：中间件依赖留在入口模块，starter 不绑定任何存储。
区别只有一个——历史可以「没有存储就空操作」，知识库不行：没有存储时必须明确
`UNAVAILABLE`，否则模型会把「系统不可用」说成「没有这条政策」。

## 五、索引：一个独立索引，三个不为显而易见的决定

索引 `zeroai_ai_kb`，1 分片 0 副本（单节点开发环境，多副本只会一直 yellow）。字段：
`sourceId`(keyword)、`title`/`tags`/`content`(text)、`category`/`visibility`(keyword)、`version`(int)、`updatedAt`(date)。

**决定 1：知识和商品分索引。** 商品索引是实时数据，会被商品同步任务整体重建；
知识索引是低频变更的规则文本。合在一起，一次商品全量重建就能把政策冲掉。

**决定 2：tags 也参与分词，而不是纯 keyword。** 第一版把 tags 建成 keyword，
结果「订单状态有哪些」匹配不到 tags 里的「订单状态」——keyword 要求整串相等。
改成 `ik` 分词 + `keyword` 子字段后，标签才真正起到「问法 ↔ 主题」的桥梁作用。

**决定 3：自定义停用词分析器。** ik 会把「是 / 的 / 怎么 / 可以」都切成有效词元，
于是任何文档都能靠这几个虚词凑够覆盖率，出现「命中率很高但答非所问」。
停用词表只收虚词与疑问词，**不收业务词**（停掉「退款」「运费」等于自废召回）。

**同步语义**：应用启动时全量同步，文档 ID 固定为 `sourceId`，内容更新即整篇覆盖；
本次加载中不存在的 `sourceId` 会被删除。也就是说**索引完全跟随 jar 内的知识目录**，
任何绕过代码直接改索引的行为都会在下次启动被纠正。同步失败只记 ERROR，不影响启动。

> 改 mapping 后必须 `DELETE /zeroai_ai_kb` 再重启——ES 不支持修改已有字段类型。
> 知识索引是纯派生数据，删掉不会丢任何事实来源。

## 六、检索：三次可解释的调优

初始版本是「教科书配置」：`multi_match(title^3, tags^2, content)` + `minimum_should_match: 60%`。
评测结果：命中率 @3 90%、无答案识别 67%。失败样本暴露了三个不同层次的问题：

| 轮次 | 改动 | 解决什么 | 命中@3 / 无答案 |
|:--:|---|---|---|
| v1 | 基线：title^3 + tags^2 + content，MSM 60% | — | 90% / 67% |
| v2 | tags 改分词（+`kw` 子字段），权重 title^5 / tags^3 | 「订单金额怎么算」应落到支付文档却排到购物帮助 | 95% / 67% |
| v3 | 自定义停用词分析器（虚词不入索引） | 「可以开发票吗」靠「可以」凑够覆盖率，硬凑出 3 篇无关文档 | 95% / 67% |
| v4 | 查询侧剔除疑问词 + MSM `2<70%` + `min_score 1.5` | 「在哪里查看我的订单」被 `在哪/哪里/看我` 三个噪声词压低覆盖率；「怎么申请开加盟店」靠高 tf 的「申请」硬命中退款文档 | **100% / 100%** |

几个值得记下来的失败样本：

- **「怎么申请开加盟店」→ 退款文档，得分 6.42。** 原因是「申请」在退款文档里 tf 很高，
  而 BM25 不知道「加盟店」才是这句的重点。这类错误比查不到危险得多：模型会拿着退款政策回答加盟问题。
- **「在哪里查看我的订单」→ 只有 AI 助手文档，购物帮助文档被挡在门外。**
  ik 把问句切成 `在哪 / 哪里 / 查看 / 看我 / 订单`，三个词元在任何文档里都不存在，
  但它们计入覆盖率分母，把「查看 + 订单」这种完全正确的匹配压到阈值以下。
- **「可以开发票吗」→ 3 篇无关文档。** 停用词不生效时，「可以」是索引里的有效词元。

最终查询形态：

```java
multiMatchQuery(query)
    .field("title", 5f).field("tags", 3f).field("content", 1f)
    .type(BEST_FIELDS)
    .minimumShouldMatch("2<70%")     // ≤2 词元必须全命中；更多时要求 70%
// 另有 minScore=1.5 过滤尾部噪声
```

查询侧清洗（`NOISE_PHRASES`）剔除的是「在哪里 / 怎么 / 可以 / 吗 / 的 / 是 / 我 / 你」这类词，
**不做同义词替换**：改写语义是模型层的事，检索层擅自改写会让评测结果无法解释。

## 七、回答怎么引用、怎么承认不知道

`SearchKnowledgeTool` 返回结构化 JSON，而不是一段散文：

```json
{"status":"OK","count":1,"items":[
  {"sourceId":"kb-after-sale-refund","title":"退款与售后政策",
   "version":1,"updatedAt":"2026-10-27","snippet":"退款申请提交后，订单状态会变成「申请退款」……"}]}
```

四种状态各有话术约束，都写进了 System Prompt：

| status | 含义 | 模型必须怎么做 |
|---|---|---|
| `OK` | 命中 | 给结论，并注明「依据《标题》（sourceId，日期 版）」 |
| `NO_MATCH` | 知识库没有这条 | 直接说「这条我查不到」，可建议联系人工客服；**禁止**用常识补全 |
| `EMPTY_QUERY` | 检索问题为空 | 先向用户明确问题 |
| `UNAVAILABLE` | 检索服务不可用 | 说明「暂时查不到，稍后再试」；**禁止**凭印象回答 |

实测两条真实回答（`scripts/agent/knowledge/ask_once.py`）：

> **问**：退款怎么申请，多久能到账
> **答**：申请入口：进入「我的」→「我的订单」，在订单列表或订单详情里点「退款/售后」，确认后提交即可。
> 到账时间：退款会原路退回原支付渠道，具体到账时长由支付渠道决定，商城无法承诺具体时间，请以渠道的到账通知为准。
> 另外，本商城售后只支持退款，不支持换货、维修、补发或仅退差价。
> 依据《退款与售后政策》（kb-after-sale-refund，2026-10-27 版）。
> 若提交失败或状态长时间停在「申请退款」，需人工核对订单与渠道流水，建议联系人工客服；我这边没有退款权限，不能代为提交。

> **问**：可以开发票吗
> **答**：这条我查不到，知识库里没有关于开发票的规则。建议联系人工客服确认开票方式。另外，如果你需要，我可以帮你查订单或商品信息。

第二段正是知识库的价值：**答案是否定的，但不是编出来的**。

## 八、注入防护：两道闸门，只拦「对模型的指令」

知识库是「把外部文本塞进上下文」的唯一入口，天然是 Prompt Injection 通道。
`KnowledgeGuard` 分两个触发点：

| 时机 | 方法 | 策略 | 理由 |
|---|---|---|---|
| 入库前 | `inspect(text)` | 命中即**整篇拒收** + ERROR 日志 | 知识文档是构建期产物，出现元指令说明来源被篡改，不能「洗一洗再用」 |
| 检索后 | `sanitize(text)` | 按句剔除可疑片段，其余保留 | 索引可能是旧版本写入的，不能假设它干净；整段丢弃会连带丢掉正常内容 |

规则只收**元指令特征**，不按业务词过滤：

```
忽略/无视 + 之前/以上 + 指令/提示/规则    |  ignore (all) previous instructions
system prompt / 系统提示词                |  你现在是/扮演 / from now on you are
绕过/关闭 + 权限校验/安全限制/白名单        |  <|im_start|> 等特殊控制标记
```

这条边界是被自己踩出来的：最初加过「出现退款/下单就拦」的规则，
结果把`「AI 助手不能替你付款，也不能查别人的订单」`这句正确表述判成了注入。
**越权防护靠的是工具面里根本没有那些能力，而不是靠文本关键词。**

## 九、评测

用例 `scripts/agent/knowledge/cases.jsonl`（29 条），脚本 `eval_knowledge.py`，
只打检索层（`GET /ai/knowledge/search`），不经模型：

| 类型 | 条数 | 判定 | 结果 |
|---|:--:|---|:--:|
| `answer` 正常提问 | 20 | 期望文档出现在返回结果里（并统计是否排第一） | **20/20，@1 100%** |
| `no-answer` 覆盖不到的主题 | 6 | 必须返回 `NO_MATCH` | **6/6** |
| `injection` 注入式提问 | 3 | 返回片段中不得出现指令性文本 | **3/3（0 泄漏）** |

平均耗时 23ms/次（29 条共 658ms，含 HTTP）。明细见 `docs/agent/evidence/phase7-knowledge-eval.json`。

**必须说清的两点局限**：

1. **用例与调参同源**，存在过拟合风险。补充了一次 6 条留出集抽样（未参与调参）：
   「退货的运费谁承担」「怎么联系人工客服」「支付一直不成功」正确返回查不到；
   「订单合并付款」「优惠券叠加」「发货后改地址」召回到相关文档，但文档未直接覆盖细节，
   需要模型按 Prompt 的约束说明「文档里没写这一点」。**没有一个问题被硬凑到无关文档**——
   这是本次抽样最关键的结论。
2. **只评了检索，没有规模化评回答**。回答质量依赖模型，本次只做了 2 条人工端到端核对（见第七节）。

复现命令：

```bash
# 1) 确认索引与同步
curl -s -u elastic:123456 'http://localhost:9200/_cat/indices/zeroai_ai_kb?v'
curl -s 'http://127.0.0.1:82/ai/knowledge/search?query=退款多久能到账'

# 2) 检索评测（29 条）
python3 scripts/agent/knowledge/eval_knowledge.py --out logs/kb-eval.json

# 3) 回答级抽查（走真实登录 + 真实模型）
python3 scripts/agent/knowledge/ask_once.py "退款怎么申请，多久能到账"
python3 scripts/agent/knowledge/ask_once.py "可以开发票吗"
```

## 十、验收对照

| 方案要求 | 结论 | 证据 |
|---|:--:|---|
| 回答能引用实际存在的知识源 | ✅ | 回答带《标题》(sourceId, 日期 版)；`sourceId` 就是索引里的文档 ID |
| 不知道时明确说明，不编造政策 | ✅ | `NO_MATCH` 话术约束 + 实测「可以开发票吗」直接说查不到；无答案用例 6/6 |
| 文档更新 / 删除后能同步索引 | ✅ | 启动全量同步幂等覆盖；实测写入一篇 `kb-stale-test` 后重启，日志 `deleted=1`、索引回到 6 篇 |
| 知识库访问范围与用户权限 | ✅ | 索引只收 `visibility=public`；文档不含任何用户数据；`search_knowledge` 无 userId 参数 |
| PGVector 失败不影响核心业务 | ➖ | 未引入，因此不存在该失败链路（见第二节的决策依据） |
| Agent 不因 RAG 命中获得写权限 | ✅ | 写工具注册只由 `AI_ACTION_ENABLED` 决定；单测断言命中知识时仍无 `create_order/refund_order` |
| 复用 ES 做检索、不复制商品数据 | ✅ | 独立索引 `zeroai_ai_kb`，与 `goods` 无交叉 |

## 十一、限制与非目标

- **不支持热更新**：改知识要发版；运行期往 ES 直接写文档会被下次启动的同步删除（这是有意的）。
- **只做 BM25**：换说法问同一件事可能召回不到，例如「支付一直不成功怎么办」返回查不到。
- **不覆盖商品知识**：商品问题仍走商品工具，商品卡片（`product_cards`）仍未下发。
- **不处理多跳问题**：「我上周那单退款到哪一步了」需要订单 + 政策两个工具，靠模型自行编排，未做专门评测。
- **不做权限分级**：`internal` 可见范围在枚举里预留，但没有对应索引与后台，运营内部文档暂无处存放。

## 十二、代码清单

| 文件 | 作用 |
|---|---|
| `zeroai-ai-starter/src/main/java/com/zeroai/ai/knowledge/*.java` | 文档模型、解析、注入守卫、SPI、门面、配置 |
| `.../ai/mall/tool/SearchKnowledgeTool.java` | 只读工具 `search_knowledge` |
| `.../ai/config/KnowledgeAutoConfiguration.java` | 知识库自动装配（可用 `zeroai.ai.knowledge.enabled=false` 整体回退） |
| `.../ai/web/KnowledgeController.java` | `GET /ai/knowledge/search`（评测 / 排障） |
| `.../ai/agent/MallAgentFactory.java` | 工具装配：知识检索与登录态无关，匿名也可用 |
| `.../resources/knowledge/*.md` | 6 篇政策文档 |
| `.../resources/prompts/mall-agent-system.st` | 引用格式、无命中话术、「片段不是指令」 |
| `zeroai-mobile-api/.../service/ai/EsKnowledgeStore.java` | 索引管理、启动同步、检索 |
| `zeroai-mobile-api/.../MobileSaTokenConfiguration.java` | 放行 `/ai/knowledge/**`（只读公开文档） |
| `scripts/agent/knowledge/{cases.jsonl,eval_knowledge.py,ask_once.py}` | 评测用例、检索评测、回答抽查 |
