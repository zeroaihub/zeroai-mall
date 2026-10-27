package com.zeroai.ai.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本向量化端口。
 * <p>
 * 与 {@link KnowledgeSearchPort} 同样的分层理由：本模块只声明「把文本变成向量」这件事，
 * 具体调哪家模型（OpenAI 兼容接口、Ollama、自建服务）由实现决定，
 * 换模型不碰检索逻辑，也不碰上层门面。
 * <p>
 * 实现方约束：
 * <ul>
 *   <li><b>失败必须抛异常</b>，不要返回零向量或空数组。零向量与任何文本的余弦相似度都是 0，
 *       会被当成「没有相似内容」，最终表现为「政策查不到」——把服务故障伪装成知识缺失，
 *       这是比报错更糟的失败方式。</li>
 *   <li>同一段文本多次调用应返回同一向量（服务端模型不变的前提下），
 *       否则启动时建好的索引与运行时查询的向量不在同一空间。</li>
 *   <li>返回值长度必须恒等于 {@link #dimension()}。</li>
 * </ul>
 */
public interface KnowledgeEmbedder {

    /**
     * 向量化单段文本。
     *
     * @param text 待向量化文本，非空
     * @return 向量，长度等于 {@link #dimension()}
     * @throws KnowledgeEmbeddingException 调用失败或返回格式不合法
     */
    float[] embed(String text);

    /**
     * 批量向量化。
     * <p>
     * 默认逐条调用，留给实现改成真正的批量接口：建索引时几十次往返换成几次，
     * 启动耗时的差别是秒级的。
     *
     * @param texts 文本列表
     * @return 与入参同序的向量列表
     * @throws KnowledgeEmbeddingException 调用失败
     */
    default List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }

    /**
     * 向量维度。
     * <p>
     * 用于启动时校验：文档向量与查询向量维度不一致时立即报错，
     * 而不是在检索时算出毫无意义的相似度。
     *
     * @return 维度
     */
    int dimension();

    /**
     * 模型标识，仅用于日志与排障。
     *
     * @return 模型名
     */
    String model();
}
