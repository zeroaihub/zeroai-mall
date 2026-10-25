package com.zeroai.domain.api.goods.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zeroai.domain.api.goods.entity.GoodsSpecification;
import com.zeroai.domain.api.goods.response.SpecificationVO;

import java.util.List;

/**
 * 商品规格表 服务类
 *
 * @since 2020-07-06
 */
public interface IGoodsSpecificationService extends IService<GoodsSpecification> {

    List<SpecificationVO> getSpecificationVOList(Long goodsId);
}
