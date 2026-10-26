package com.zeroai.ai.mall.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商城搜索端口。
 * <p>
 * 搜索依赖 Elasticsearch 与检索侧组装逻辑，实现位于入口模块 {@code zeroai-mobile-api}
 * （{@code SearchApplicationService} 已有完整实现）。本模块只声明端口、不重复实现，
 * 避免 AI 模块反向依赖入口层造成循环依赖。
 * <p>
 * 实现方约束：必须复用既有检索链路，禁止在此方法内引入新的查询语义，
 * 否则 AI 通道与 H5 通道的搜索结果会出现不一致。
 */
public interface MallSearchPort {

    /**
     * 按关键词检索在售商品。
     *
     * @param keyword 检索词，非空
     * @param limit   返回条数上限，实现方必须做上限保护
     * @return 命中商品摘要，按相关度排序；无命中返回空列表，不返回 null
     */
    List<GoodsBrief> search(String keyword, int limit);

    /**
     * 商品摘要，只保留模型回答所需的最小字段集。
     *
     * @param goodsId     商品 ID
     * @param name        商品名称
     * @param retailPrice 零售价
     * @param sales       销量
     * @param picUrl      主图地址
     */
    record GoodsBrief(Long goodsId, String name, BigDecimal retailPrice, Integer sales, String picUrl) {
    }
}
