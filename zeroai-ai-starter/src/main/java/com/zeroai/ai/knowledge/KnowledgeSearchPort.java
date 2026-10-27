package com.zeroai.ai.knowledge;

import java.util.List;

/**
 * 知识检索端口。
 * <p>
 * 与 {@link com.zeroai.ai.mall.port.MallSearchPort} 同样的分层理由：
 * AI starter 只声明「怎么查」，具体检索实现依赖中间件（本项目复用 Elasticsearch），
 * 落在入口模块。starter 不绑定任何一种检索引擎，后续换成向量库时也只换实现。
 * <p>
 * 实现方约束：
 * <ul>
 *   <li>只返回 {@link KnowledgeVisibility#PUBLIC} 文档，过滤必须在检索侧完成，
 *       不能指望调用方再筛一遍；</li>
 *   <li>不做写入、不做索引变更，索引维护由同步任务负责；</li>
 *   <li>检索故障抛 {@link RuntimeException}，由门面统一转成「检索不可用」状态——
 *       不要把故障吞成空列表，否则模型会把「系统挂了」表达成「没有这条政策」。</li>
 * </ul>
 */
public interface KnowledgeSearchPort {

    /**
     * 按自然语言问题检索知识片段。
     *
     * @param query 用户问题，非空
     * @param limit 返回条数上限，实现方必须做上限保护
     * @return 命中列表，按相关度倒序；无命中返回空列表，不返回 null
     */
    List<KnowledgeHit> search(String query, int limit);
}
