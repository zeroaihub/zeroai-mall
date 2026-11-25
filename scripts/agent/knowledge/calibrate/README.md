# 知识检索阈值标定

本目录是**标定工具**，不是生产代码。用途：语料或 embedding 模型变更后，重新确定
`min-should-match` / `min-score` / `vector.min-score` / `vector-only-min-score` 四个参数。

## 前提

| 依赖 | 说明 |
|---|---|
| Elasticsearch | 已启动，且索引里是**当前语料**（启动应用即自动同步，`zeroai_ai_kb`） |
| IK 分词插件 | 索引 mapping 使用 `kb_index` 分析器，缺插件则分数完全不可比 |
| embedding 服务 | 真实服务，不能用离线代理代替。本机 `ollama serve` + `bge-m3` |
| Java 21 + 项目类路径 | `VectorExport` 直接复用 `KnowledgeCatalog` / `KnowledgeChunker`，与生产切块完全一致 |

所有命令都在**项目根目录**执行。

## 步骤

### 1. 确认 ES 里是新语料

```bash
curl -s -u elastic:123456 "http://127.0.0.1:9200/zeroai_ai_kb/_count"
# 应为当前文档数（18 篇售后语料）
```

数量不对就先启动一次应用完成同步。

### 2. 导出向量分数

```bash
CP="zeroai-ai-starter/target/classes;$(cat /tmp/cp.txt)"   # 类路径见文末
mkdir -p target/knowledge-calibration
javac -proc:none -encoding UTF-8 -cp "$CP" -d target/knowledge-calibration \
      scripts/agent/knowledge/calibrate/VectorExport.java
java -Dfile.encoding=UTF-8 -cp "target/knowledge-calibration;$CP" VectorExport

# 留出集（也是新问法，必须单独导出）
java -Dfile.encoding=UTF-8 -cp "target/knowledge-calibration;$CP" VectorExport \
     scripts/agent/knowledge/calibrate/holdout.jsonl \
     target/knowledge-calibration/holdout_vectors.json
```

### 3. 跑标定

```bash
python scripts/agent/knowledge/calibrate/final_check.py
python scripts/agent/knowledge/calibrate/msm_scan.py
python scripts/agent/knowledge/calibrate/hybrid_calibrate.py
```

## 脚本职责

| 文件 | 作用 |
|---|---|
| `bm25_calibrate.py` | 基础库：复刻 `stripNoise` 与 `EsKnowledgeStore.search` 的 DSL，提供网格扫描。**其他脚本依赖它** |
| `msm_scan.py` | `minimum_should_match` 阶梯写法对比，同时评价训练集与留出集 |
| `hybrid_calibrate.py` | 完整 hybrid 模拟：向量阈值过滤 → 文档去重 → RRF 融合 → 门禁 |
| `final_check.py` | 最终参数的平台确认（参数敏感性，判断是否过拟合尖峰） |
| `VectorExport.java` | 导出「用例 × 知识块」余弦分数，供 Python 侧做阈值扫描 |
| `holdout.jsonl` | 14 条留出用例，措辞与训练集刻意不同，用于检验泛化 |

## 判据（本项目固守的原则）

**宁可查不到，也不硬凑。** 具体到选参：

1. 先取「库外误召回 = 0」的参数区间；
2. 在区间内取召回最高的；
3. 若区间只有一个点，看它两侧是否陡降——陡降说明是过拟合尖峰，需回到步骤 1 放宽；
4. 参数落在平台区中部，不要取边界值。

## 2026-11-25 的标定结果

| 参数 | 值 | 依据 |
|---|---|---|
| `min-should-match` | `2<100% 4<60%` | 训练 22/22、库外 0/8；留出 8/10、0/4 |
| `min-score`（BM25） | `8.5` | 8.0~9.0 零误召回平台，取中点 |
| `vector.min-score` | `0.65` | 唯一零误召回点（0.60~0.64 会误召回「开发票/货到付款」） |
| `vector-only-min-score` | `0.65` | 与向量阈值同值，语义为「向量足够自信才可单独作答」 |

完整数据见 [`../../../docs/agent/evidence/2026-11-25-aftersale-threshold-calibration.md`](../../../docs/agent/evidence/2026-11-25-aftersale-threshold-calibration.md)。

## 已知限制

- **参数与语料强耦合**：`min-score=8.5` 是在 18 篇 / 90 块上测的。语料规模翻倍会改变
  IDF，绝对分数随之漂移，必须重跑本文档的流程，不能沿用。
- **留出集只有 14 条**：能发现明显的过拟合，但不足以支撑统计显著性结论。
- **降级未单独标定**：向量服务不可用时退回纯 BM25，用的是同一套 BM25 参数，
  实测训练集召回 13/22。若降级质量重要，需要为 fallback 单独设计参数集。

## 类路径怎么来

```bash
JAVA_HOME="C:/Users/13153/.jdks/ms-21.0.12" \
  "/e/IntelliJ IDEA 2026.1.3/plugins/maven/lib/maven3/bin/mvn" \
  -pl zeroai-ai-starter dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -q
```
