package com.zeroai.domain.goods.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zeroai.domain.api.goods.entity.GoodsAttribute;
import com.zeroai.domain.api.goods.mapper.GoodsAttributeMapper;
import com.zeroai.domain.api.goods.service.IGoodsAttributeService;
import org.springframework.stereotype.Service;

/**
 * 商品参数表 服务实现类
 *
 * @since 2020-07-06
 */
@Service
public class GoodsAttributeServiceImpl extends ServiceImpl<GoodsAttributeMapper, GoodsAttribute> implements IGoodsAttributeService {

}
