package com.zeroai.domain.promotion.strategy.diamond;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zeroai.domain.api.promotion.entity.Diamond;
import com.zeroai.domain.api.goods.entity.Goods;
import com.zeroai.domain.api.promotion.response.DiamondGoodsResVO;

/**
 * 金刚位跳转策略接口
 */
public interface DiamondJumpTypeInterface {

    DiamondGoodsResVO getGoods(Page<Goods> page, Diamond diamond);

    Integer getType();
}
