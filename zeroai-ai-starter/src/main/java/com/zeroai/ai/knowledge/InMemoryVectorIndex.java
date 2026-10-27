package com.zeroai.ai.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程内向量索引：暴力余弦检索。
 * <p>
 * <b>为什么不用向量数据库</b>：本项目知识库是 6 篇政策、切块后几十条的量级。
 * 暴力遍历几十个 1024 维向量是微秒级操作，比一次 HTTP 往返还快。
 * 引入 PGVector 意味着多一个容器、一套连接池、一条失败链路，
 * 而它带来的收益（ANN 索引加速）在几十条数据上等于零——
 * 这正是本项目一贯的取舍：<b>能不加的基础设施就不加</b>。
 * <p>
 * 需要换成真正的向量库时，实现 {@link KnowledgeSearchPort} 换掉
 * {@link VectorKnowledgeStore} 即可，本类不对外暴露。
 * <p>
 * <b>并发语义</b>：索引整体替换而不是原地修改。检索线程读到的永远是一个完整快照，
 * 不会出现「读到一半索引被清空」的半态；代价是重建瞬间内存里有两份索引，
 * 对本项目的量级可以忽略。
 */
public class InMemoryVectorIndex {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorIndex.class);

    /** 一条索引记录。 */
    private record Entry(KnowledgeChunker.Chunk chunk, float[] vector) {
    }

    /** 当前索引快照，只整体替换，不原地改。 */
    private volatile List<Entry> entries = List.of();

    /**
     * 重建索引。
     * <p>
     * 向量计算全部成功后才替换快照：中途 embedding 失败时，旧索引保持可用，
     * 而不是变成「空索引 + 服务看起来正常」这种最坏状态。
     *
     * @param chunks   知识块
     * @param embedder 向量化端口
     * @return 实际入索引的块数
     * @throws KnowledgeEmbeddingException 向量化失败
     */
    public int rebuild(List<KnowledgeChunker.Chunk> chunks, KnowledgeEmbedder embedder) {
        List<String> texts = chunks.stream().map(KnowledgeChunker.Chunk::text).toList();
        List<float[]> vectors = embedder.embedAll(texts);
        if (vectors.size() != chunks.size()) {
            throw new KnowledgeEmbeddingException(
                    "向量条数与知识块数不符：chunks=" + chunks.size() + " vectors=" + vectors.size());
        }
        List<Entry> built = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = vectors.get(i);
            if (vector == null || vector.length != embedder.dimension()) {
                throw new KnowledgeEmbeddingException(
                        "第 " + i + " 个向量维度不符：期望 " + embedder.dimension()
                                + "，实际 " + (vector == null ? 0 : vector.length));
            }
            built.add(new Entry(chunks.get(i), normalize(vector)));
        }
        this.entries = List.copyOf(built);
        log.info("向量索引重建完成 chunks={} dimension={} model={}", built.size(), embedder.dimension(), embedder.model());
        return built.size();
    }

    /**
     * 是否已建好索引。
     *
     * @return 有数据返回 true
     */
    public boolean isReady() {
        return !entries.isEmpty();
    }

    /**
     * 索引条数。
     *
     * @return 块数
     */
    public int size() {
        return entries.size();
    }

    /**
     * 按向量检索。
     * <p>
     * 同一个 {@code sourceId} 只保留最高分的一块：回答里引用同一篇文档三次
     * 不会带来增量信息，却会挤掉其他文档的名额。
     *
     * @param query    查询向量，内部会归一化（不改入参）
     * @param limit    返回条数
     * @param minScore 余弦下限，低于该值丢弃
     * @return 命中列表，按相似度降序
     */
    public List<KnowledgeHit> search(float[] query, int limit, double minScore) {
        List<Entry> snapshot = this.entries;
        if (snapshot.isEmpty() || query == null || query.length == 0) {
            return List.of();
        }
        float[] normalized = normalize(query);
        List<Scored> scored = new ArrayList<>(snapshot.size());
        for (Entry entry : snapshot) {
            if (entry.vector().length != normalized.length) {
                // 维度不一致只可能是模型换了却没重建索引，跳过而不是算出一个假分数
                log.warn("查询向量维度与索引不符，已跳过该条：index={} query={}",
                        entry.vector().length, normalized.length);
                continue;
            }
            double similarity = dot(entry.vector(), normalized);
            if (similarity < minScore) {
                continue;
            }
            scored.add(new Scored(entry.chunk(), similarity));
        }
        if (scored.isEmpty()) {
            return List.of();
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        Map<String, KnowledgeHit> best = new LinkedHashMap<>();
        for (Scored item : scored) {
            best.putIfAbsent(item.chunk().sourceId(), item.chunk().toHit(item.score()));
            if (best.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(best.values());
    }

    /**
     * 计算索引内某文档的最高分，仅用于排障。
     *
     * @param sourceId 文档标识
     * @return 块数
     */
    public long countBySource(String sourceId) {
        return entries.stream().filter(entry -> entry.chunk().sourceId().equals(sourceId)).count();
    }

    /**
     * 归一化到单位长度。
     * <p>
     * 索引与查询两侧都归一化后，点积直接等于余弦相似度，省掉每次比较的开方。
     *
     * @param vector 原向量
     * @return 单位向量；零向量原样返回（后续相似度为 0，会被阈值过滤）
     */
    static float[] normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        if (sum <= 0) {
            return vector.clone();
        }
        double norm = Math.sqrt(sum);
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = (float) (vector[i] / norm);
        }
        return result;
    }

    /**
     * 点积。
     *
     * @param a 单位向量
     * @param b 单位向量
     * @return 余弦相似度
     */
    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    /**
     * 打分中间结果。
     *
     * @param chunk 知识块
     * @param score 相似度
     */
    private record Scored(KnowledgeChunker.Chunk chunk, double score) {
    }
}
