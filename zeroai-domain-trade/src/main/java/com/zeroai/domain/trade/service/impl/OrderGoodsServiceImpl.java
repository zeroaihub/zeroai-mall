package com.zeroai.domain.trade.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zeroai.domain.api.trade.entity.OrderGoods;
import com.zeroai.domain.api.trade.mapper.OrderGoodsMapper;
import com.zeroai.domain.api.trade.service.IOrderGoodsService;
import org.springframework.stereotype.Service;

/**
 * 订单商品表 服务实现类
 *
 * @since 2020-08-11
 */
@Service
public class OrderGoodsServiceImpl extends ServiceImpl<OrderGoodsMapper, OrderGoods> implements IOrderGoodsService {

}
