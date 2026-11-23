package com.zeroai.ai.mall.event;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品卡片事件载荷：把本轮搜索命中的商品下发到前端渲染成可点击的卡片。
 * <p>
 * 存在的理由是「加购入口不能把用户赶出对话」：早前用户想买对话里提到的商品，
 * 只能离开 AI 页面去商品详情页加购再回来，链路断在中间。
 * 卡片上带 goodsId，前端点「加入购物车」直接走加购接口，不需要模型再介入。
 * <p>
 * 只带展示与加购所需的字段，不含库存编码、成本价等内部数据。
 *
 * @param keyword 检索关键词，用于卡片分组标题
 * @param items   商品卡片列表，按相关度排序
 */
public record ProductCards(String keyword, List<Card> items) {

    /**
     * 单个商品卡片。
     *
     * @param goodsId 商品 ID，加购时回传
     * @param name    商品名称
     * @param price   零售价
     * @param sales   销量，仅用于展示
     * @param picUrl  主图地址，可为空
     */
    public record Card(Long goodsId, String name, BigDecimal price, Integer sales, String picUrl) {
    }
}
