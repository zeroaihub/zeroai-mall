package com.zeroai.ai.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量检索实现：启动时把知识文档切块并向量化，运行期用余弦相似度召回。
 * <p>
 * 与 {@code EsKnowledgeStore} 的分工：
 * <ul>
 *   <li>ES 实现放在入口模块，因为它依赖 {@code RestHighLevelClient}；</li>
 *   <li>本类只依赖一个 {@link KnowledgeEmbedder} 端口（HTTP 调用），
 *       所以留在 starter 里——换个 embedding 供应商只需要改配置。</li>
 * </ul>
 * <p>
 * <b>为什么不落一个向量库</b>：知识块是 jar 内的静态内容，量级几十条，
 * 每次启动重算一遍向量是秒级成本，换来的是「没有任何新增基础设施」。
 * 真正需要持久化的场景（语料上万条、启动预算紧）再换实现，
 * 上层 {@link KnowledgeSearchPort} 与门面都不用动。
 * <p>
 * <b>失败语义</b>：向量化失败时索引保持上一次成功的内容；从未成功过时
 * {@link #search} 抛 {@link IllegalStateException}，由门面转成
 * {@code UNAVAILABLE}——模型会说「检索暂不可用」，而不是「知识库没有这条政策」。
 * 这两句话对用户的意义完全不同，不能混。
 */
public class VectorKnowledgeStore implements VectorKnowledgePort, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorKnowledgeStore.class);

    private final KnowledgeEmbedder embedder;
    private final KnowledgeProperties properties;
    private final InMemoryVectorIndex index = new InMemoryVectorIndex();

    /**
     * @param embedder   向量化端口，为 null 表示未配置（此时检索直接不可用）
     * @param properties 知识库配置
     */
    public VectorKnowledgeStore(KnowledgeEmbedder embedder, KnowledgeProperties properties) {
        this.embedder = embedder;
        this.properties = properties;
    }

    /**
     * 启动时建向量索引。
     * <p>
     * 失败只记 ERROR 不阻断启动：知识库是可选能力，embedding 服务抖动不应该让商城起不来。
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!properties.resolveMode().needsEmbedding()) {
            // 纯 BM25 模式：不构建向量索引，省掉启动时几十次 embedding 调用
            log.debug("当前检索模式为 {}，跳过向量索引构建", properties.resolveMode().code());
            return;
        }
        if (!properties.isSyncOnStartup()) {
            log.info("向量索引启动构建已关闭（sync-on-startup=false），检索前需手工触发 sync()");
            return;
        }
        try {
            Report report = sync();
            log.info("向量索引构建完成 documents={} chunks={} rejected={} model={}",
                    report.documents(), report.chunks(), report.rejected(),
                    embedder == null ? "none" : embedder.model());
        } catch (Exception e) {
            log.error("向量索引构建失败，向量分支不可用（hybrid 模式会自动退回 BM25）：{}", e.getMessage());
        }
    }

    /**
     * 全量重建向量索引。
     *
     * @return 构建统计
     * @throws KnowledgeEmbeddingException 向量化失败
     */
    public Report sync() {
        if (embedder == null) {
            throw new KnowledgeEmbeddingException("embedding 未配置，无法构建向量索引");
        }
        KnowledgeCatalog.LoadResult catalog = KnowledgeCatalog.load(properties.getLocation());
        List<KnowledgeDocument> documents = catalog.publicDocuments();

        List<KnowledgeChunker.Chunk> chunks = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            chunks.addAll(KnowledgeChunker.split(document,
                    properties.getVector().getChunkSize(),
                    properties.getVector().getMinChunkChars(),
                    properties.getVector().isPrefixTitle()));
        }
        if (chunks.isEmpty()) {
            log.warn("没有可向量化的知识块，向量检索将始终返回不可用：location={}", properties.getLocation());
        }
        int indexed = index.rebuild(chunks, embedder);
        return new Report(documents.size(), indexed, catalog.rejected().size());
    }

    /**
     * 检索知识片段。
     *
     * @param query 检索问题
     * @param limit 条数上限
     * @return 命中列表
     * @throws IllegalStateException 索引未就绪或向量化失败，由门面转成「不可用」
     */
    @Override
    public List<KnowledgeHit> search(String query, int limit) {
        if (embedder == null) {
            throw new IllegalStateException("embedding 未配置，向量检索不可用");
        }
        if (!index.isReady()) {
            // 不能返回空列表：那会被解释成「知识库里没有这条政策」，而事实是「索引没建起来」
            throw new IllegalStateException("向量索引未就绪（chunks=" + index.size() + "）");
        }
        float[] vector;
        try {
            vector = embedder.embed(query);
        } catch (RuntimeException e) {
            throw new IllegalStateException("查询向量化失败：" + e.getMessage(), e);
        }
        return index.search(vector, limit, properties.getVector().getMinScore());
    }

    /**
     * 当前索引块数，用于健康检查与排障。
     *
     * @return 块数
     */
    public int size() {
        return index.size();
    }

    /**
     * 构建统计。
     *
     * @param documents 公开文档数
     * @param chunks    入索引的知识块数
     * @param rejected  未通过校验的文档数
     */
    public record Report(int documents, int chunks, int rejected) {
    }
}
