package com.zeroai.ai.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索路由：按配置在 BM25、向量、混合三种模式间切换。
 * <p>
 * <b>为什么要有这一层，而不是让每个实现自己判断开关</b>：
 * 「用哪种检索」是部署决策，不是实现细节。把决策收敛到一个类里，
 * 好处是配置与实际行为永远一致（不存在「配了 vector 但实际还在走 ES」），
 * 并且三种模式的失败语义、日志、回退策略可以放在同一处对比着看。
 * 上层 {@link KnowledgeService} 只认 {@link KnowledgeSearchPort}，
 * 换模式不需要重新装配任何 Bean。
 * <p>
 * <b>混合模式为什么用 RRF 而不是加权分数</b>：BM25 分数（实测正确命中 2.4 以上）
 * 与余弦相似度（0..1）没有可比性，任何「0.3 × bm25 + 0.7 × cosine」的加权
 * 都是拍脑袋，且换语料就得重调。倒数排名融合只用排名：两路都排第一的文档
 * 一定优于只在一路排第一的，这个判断不依赖分数量纲。
 * <p>
 * <b>失败降级</b>：混合模式下向量分支挂掉时，按 {@code hybrid.fallback-to-bm25}
 * 决定是退回纯 BM25 还是整体报不可用。默认退回——语义召回是增强项，
 * 它没了不代表字面检索也错了。
 */
public class KnowledgeSearchRouter implements KnowledgeSearchPort {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchRouter.class);

    private final Bm25KnowledgePort bm25;
    private final VectorKnowledgePort vector;
    private final KnowledgeProperties properties;

    /**
     * @param bm25       BM25 实现，可为 null（入口模块未接入 ES）
     * @param vector     向量实现，可为 null（未配置 embedding 或模式为 bm25）
     * @param properties 知识库配置
     */
    public KnowledgeSearchRouter(Bm25KnowledgePort bm25, VectorKnowledgePort vector, KnowledgeProperties properties) {
        this.bm25 = bm25;
        this.vector = vector;
        this.properties = properties;
        log.info("知识检索路由已装配 mode={} bm25={} vector={}",
                properties.resolveMode().code(),
                bm25 == null ? "missing" : "ready",
                vector == null ? "missing" : "ready");
    }

    @Override
    public List<KnowledgeHit> search(String query, int limit) {
        return switch (properties.resolveMode()) {
            case BM25 -> searchBm25(query, limit);
            case VECTOR -> searchVector(query, limit);
            case HYBRID -> searchHybrid(query, limit);
        };
    }

    @Override
    public KnowledgeMode mode() {
        return properties.resolveMode();
    }

    /**
     * 纯 BM25 检索。
     *
     * @param query 问题
     * @param limit 条数
     * @return 命中
     */
    private List<KnowledgeHit> searchBm25(String query, int limit) {
        if (bm25 == null) {
            throw new IllegalStateException("未接入 BM25 检索实现（当前 mode=bm25）");
        }
        return bm25.search(query, limit);
    }

    /**
     * 纯向量检索。
     *
     * @param query 问题
     * @param limit 条数
     * @return 命中
     */
    private List<KnowledgeHit> searchVector(String query, int limit) {
        if (vector == null) {
            throw new IllegalStateException("向量检索未启用（mode=vector 但缺少 embedding 配置或索引未构建）");
        }
        return vector.search(query, limit);
    }

    /**
     * 混合检索：两路各查一次，RRF 融合。
     *
     * @param query 问题
     * @param limit 条数
     * @return 融合后的命中
     */
    private List<KnowledgeHit> searchHybrid(String query, int limit) {
        List<KnowledgeHit> lexical = null;
        RuntimeException lexicalError = null;
        if (bm25 != null) {
            try {
                lexical = bm25.search(query, limit);
            } catch (RuntimeException e) {
                lexicalError = e;
                log.warn("混合检索的 BM25 分支失败：{}", e.getMessage());
            }
        }

        List<KnowledgeHit> semantic = null;
        RuntimeException semanticError = null;
        if (vector != null) {
            try {
                semantic = vector.search(query, limit);
            } catch (RuntimeException e) {
                semanticError = e;
                log.warn("混合检索的向量分支失败：{}", e.getMessage());
            }
        }

        if (lexical == null && semantic == null) {
            throw new IllegalStateException("混合检索两路都不可用：bm25="
                    + describe(lexicalError) + " vector=" + describe(semanticError));
        }
        if (semantic == null) {
            if (properties.getHybrid().isFallbackToBm25()) {
                log.warn("向量分支不可用，混合检索退化为 BM25：{}", describe(semanticError));
                return lexical == null ? List.of() : lexical;
            }
            throw new IllegalStateException("向量分支不可用且未开启回退：" + describe(semanticError));
        }
        if (lexical == null) {
            log.warn("BM25 分支不可用，混合检索退化为纯向量：{}", describe(lexicalError));
            return semantic;
        }
        return fuse(lexical, semantic, limit);
    }

    /**
     * 倒数排名融合。
     * <p>
     * 每份结果在第 r 名（从 1 开始）贡献 {@code 1/(k+r)}，同一 sourceId 的两路贡献相加。
     * 同一文档在两路都靠前时分数翻倍，这正是「字面与语义都认为相关」应有的待遇。
     *
     * @param lexical  字面检索结果
     * @param semantic 语义检索结果
     * @param limit    返回条数
     * @return 融合结果，按融合分倒序
     */
    private List<KnowledgeHit> fuse(List<KnowledgeHit> lexical, List<KnowledgeHit> semantic, int limit) {
        int k = Math.max(1, properties.getHybrid().getRrfK());
        double threshold = properties.getHybrid().getMinScore();

        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, KnowledgeHit> representatives = new LinkedHashMap<>();
        accumulate(lexical, k, scores, representatives);
        accumulate(semantic, k, scores, representatives);

        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort(Comparator.comparingDouble((Map.Entry<String, Double> entry) -> entry.getValue()).reversed());

        List<KnowledgeHit> hits = new ArrayList<>(limit);
        for (Map.Entry<String, Double> entry : ranked) {
            if (entry.getValue() < threshold) {
                continue;
            }
            KnowledgeHit source = representatives.get(entry.getKey());
            if (source == null) {
                continue;
            }
            // 片段沿用首次出现的来源（BM25 优先）：字面命中的高亮片段更贴近提问用词
            hits.add(new KnowledgeHit(source.sourceId(), source.title(), source.category(), source.version(),
                    source.updatedAt(), source.snippet(), entry.getValue()));
            if (hits.size() >= limit) {
                break;
            }
        }
        return hits;
    }

    /**
     * 累加一路结果的排名分。
     *
     * @param hits            一路结果
     * @param k               平滑常数
     * @param scores          分数累加器
     * @param representatives 代表片段（首次出现者胜出）
     */
    private void accumulate(List<KnowledgeHit> hits, int k,
                            Map<String, Double> scores, Map<String, KnowledgeHit> representatives) {
        if (hits == null) {
            return;
        }
        int rank = 0;
        for (KnowledgeHit hit : hits) {
            if (hit == null || hit.sourceId() == null || hit.sourceId().isBlank()) {
                continue;
            }
            rank++;
            scores.merge(hit.sourceId(), 1.0D / (k + rank), Double::sum);
            representatives.putIfAbsent(hit.sourceId(), hit);
        }
    }

    /**
     * 异常转简短说明，用于日志与错误信息。
     *
     * @param error 异常，可为 null
     * @return 说明文本
     */
    private static String describe(RuntimeException error) {
        return error == null ? "未接入" : error.getMessage();
    }
}
