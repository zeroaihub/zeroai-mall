# ADR-004 — 售后语料改用 PGVector 持久化向量检索

- **状态**：Accepted
- **日期**：2026-11-25
- **决策范围**：知识库向量存储的实现方式、售后语料的归属与替换策略、embedding 基线
- **关联**：PHASE-7（BM25/向量/混合三模式）、`docs/agent/PHASE-10-PGVECTOR-JD-AFTER-SALE.md`

## 1. 背景

现有知识库是 6 篇人工撰写的政策文档，向量实现是进程内暴力余弦
（`VectorKnowledgeStore` + `InMemoryVectorIndex`），每次启动重算，不持久化。
PHASE-7 当时明确拒绝 PGVector，理由是「11 个块不值得引入一个数据库」——该判断在当时成立。

本次新增 15 篇爬取的售后 SOP（正文 38,804 字，500 字切块 90 块），并提出三个新约束：

1. 售后口径**替换**原有 `kb-after-sale-refund.md`，直接进导购助手的知识面，不再单独做语料域隔离；
2. 使用本机已安装的 `bge-m3`（Ollama，1024 维），不引入外部 embedding 服务；
3. 本期只处理文字，61 张图片不解析。

新增语料改变了工作负载：文档会随爬取批次追加，需要增量同步、元数据过滤和持久化；
进程内索引每次启动全量重算的代价从「11 次」变成「100+ 次」，且无法保留同步状态。

## 2. 备选方案

| 方案 | 优点 | 代价 | 结论 |
|---|---|---|---|
| **A. 进程内索引（维持现状）** | 零新增基础设施；实现已完成 | 每次启动全量重算；无持久化；无元数据过滤；语料增长后启动变慢 | 否 |
| **B. Spring AI PgVectorStore** | 少写代码，自带 schema 初始化与过滤表达式 | 要求 `EmbeddingModel` Bean，与现有 `OpenAIEmbeddingClient` 形成两套 embedding 配置；Spring AI 2.0.2 的 pgvector 模块坐标未核实 | 否 |
| **C. 直连 JDBC + 自建 `PgVectorKnowledgeStore`** | 与现有 embedding 客户端同一套配置；SQL 可读可调；无版本耦合；连接池可私有化 | 自维护建表、upsert、维度校验，约 300 行 + 运维脚本 | **是** |
| **D. `langchain4j-pgvector`** | 与 LangChain4j 学习线一致 | 知识层是引擎无关的共享设施，引入 langchain4j 模块会破坏 ADR-003 的隔离意图 | 否 |

## 3. 决策

1. **存储实现放 `zeroai-mobile-api`**，实现 `VectorKnowledgePort`，与 `EsKnowledgeStore` 对称；
   `zeroai-ai-starter` 只增加 `vector.store` 开关属性，不新增数据库依赖。
2. **直连 JDBC**：`HikariDataSource` 由 `PgVectorKnowledgeStore` 私有持有，**不注册为 Spring Bean**；
   使用 `JdbcTemplate` + `?::vector` 字面量，不引入 pgvector-java。
3. **二选一装配**：`zeroai.ai.knowledge.vector.store=memory|pgvector`，
   `memory` 侧加 `@ConditionalOnProperty(matchIfMissing=true)`。原因是检索路由用
   `ObjectProvider.getIfAvailable()` 按类型取候选，两个实现同时存在会抛
   `NoUniqueBeanDefinitionException`。
4. **售后语料替换原售后文档**：删除 `kb-after-sale-refund.md`，新增 13 篇
   `kb-aftersale-*.md`，与原 5 篇通用规则同目录、同 `visibility: public`。
   不再引入 `corpus` 列——只有一套售后口径，隔离已无对象。
5. **embedding 基线**：`bge-m3`（Ollama），维度 **1024**，表定义 `vector(1024)`。
6. **维度作为硬校验**：启动时用 `format_type()` 读取表定义，与 `embedding.dimension` 不一致
   直接拒绝检索，而不是返回错空间的相似度。

## 4. 理由

- **为什么不用 Spring AI 的 store**：本项目已经有一套自研的 OpenAI 兼容 embedding 客户端，
  覆盖了空密钥（Ollama）、维度校验、批量、超时四件事。再引入 `EmbeddingModel` 抽象，
  必然出现两份维度与超时配置，且两边互相不知道对方被改过——这类配置漂移比多写 300 行更贵。
- **为什么连接池不能是 Bean**：`DataSourceAutoConfiguration` 的条件是「容器里没有 DataSource」。
  一旦多出一个 Bean，Spring Boot 会放弃装配主数据源，MyBatis 有概率绑定到 PG 上，
  商城查询打到知识库库，属于灾难级故障。私有化是从根上消除这个可能。
- **为什么语料可以替换**：用户明确要求「替换掉原来的售后口径，放在导购助手里」。
  替换消除了 PHASE-10 初稿里最严重的 P0（两套政策互相矛盾），代价是
  原「只支持退款、不支持换货维修」的口径不再成立，需要提示词补能力边界（见第 6 节）。

## 5. 代价与影响

- 多一个中间件与失败链路：PG 不可用时知识检索返回 `UNAVAILABLE`，但应用仍可启动（同步失败只记 ERROR）。
- 需要运维建表：`pgvector/init/01-schema.sql` 只在数据目录为空时自动执行，已有库需手工执行。
- 换 embedding 模型（维度变化）必须重建表并清空旧向量。
- 售后语料来自外部平台文档，去品牌后仍存在版权与合规边界，需限制部署范围。

## 6. 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| 新增 DataSource Bean 顶替主数据源 | 高 | 连接池私有持有；代码审查检查容器内 DataSource 数量；启动日志确认主库仍是 MySQL |
| 表维度与 embedding 不一致 | 高 | 启动 `format_type()` 硬校验 + `embedding_model` 落库 |
| 售后政策承诺超出系统能力（换货/维修/上门取件） | 中 | `guide-system.st` 第 8 条：只解释规则、不承诺代为操作，引导整单退款或人工客服 |
| 图片信息丢失（61 张，部分为规则截图） | 中 | 本期明确不解析；空节直接删除，不用占位符伪造内容 |
| 去品牌后语义失真 | 中 | 平台账户体系（京豆/白条/PLUS/京享值）整块剔除而非改写；表格按表头结构化；逐篇人工复核 |
| 增量同步残留旧块 | 中 | 按 `chunk_index >= 新块数` 删除 + 收尾清理孤儿 `source_id` |

## 7. 验证

**已执行且通过**

- `mvn -pl zeroai-ai-starter test`：269 通过 / 0 失败 / 1 跳过（手工 Spike）；
- `KnowledgeCatalogTest` / `VectorKnowledgeStoreTest` / `KnowledgeServiceTest`：25 通过；
  18 篇文档、chunk-size 300 时 152 块（平均 8.4 块/篇）；
- `mvn -pl zeroai-mobile-api -am -DskipTests compile`：BUILD SUCCESS；
- **向量阈值标定**（真实 bge-m3、30 条用例）：可答 top1 中位 0.726、库外最高 0.648；
  `vector.min-score=0.65` 时 hit@3 81.8%、库外误召回 0/8；
- **BM25 阈值标定**（真实 ES 7.14 + IK）：原参数 `2<70%` + `1.5` 会误召回 3/8 库外问题；
  改为 `2<100% 4<60%` + `8.5` 后，训练集零误召回。`8.0~9.0` 是平台区；
- **hybrid 阈值与融合**（真实两路 + RRF）：训练集 hit@3 22/22、库外 0/8；
  14 条留出用例 hit@3 8/10、库外 0/4；
- **端到端对话验证**（应用以 `mode=hybrid` 启动）：可答问题命中、库外问题拒答、
  「BM25 零命中由向量补位」三条均通过。数据见
  `evidence/2026-11-25-aftersale-threshold-calibration.md`，复现脚本见
  `scripts/agent/knowledge/calibrate/README.md`。

**尚未执行（环境原因）**

- PGVector 端到端同步与检索：Postgres 容器未启动。本轮 hybrid 验证用的是
  `vector.store=memory`，两者共用同一套向量分数与阈值，但持久化链路的建表、
  增量同步、维度校验仍需按 `PHASE-10` 第 10 节实跑。

## 8. 重新评估条件

- 语料回落到百块以内且不再增长 → 可切回 `store=memory`；
- 需要按语料域并存（例如同时保留本商城政策与外部政策）→ 升级为双 `KnowledgeService` + 双工具；
- 需要多模态（图片 OCR / 图文向量）→ 重新评估存储与切块方案；
- embedding 模型换代或维度变化 → 重建表并重跑评测，不复用旧阈值。
