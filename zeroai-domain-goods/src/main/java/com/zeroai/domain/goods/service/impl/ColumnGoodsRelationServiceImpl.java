package com.zeroai.domain.goods.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zeroai.domain.api.goods.entity.ColumnGoodsRelation;
import com.zeroai.domain.api.goods.mapper.ColumnGoodsRelationMapper;
import com.zeroai.domain.api.goods.service.IColumnGoodsRelationService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 栏目商品关联表 服务实现类
 *
 * @since 2020-10-10
 */
@Service
@AllArgsConstructor
public class ColumnGoodsRelationServiceImpl extends ServiceImpl<ColumnGoodsRelationMapper, ColumnGoodsRelation> implements IColumnGoodsRelationService {

    private ColumnGoodsRelationMapper columnGoodsRelationMapper;

    @Override
    public Integer getGoodsNum(Long columnId) {
        return columnGoodsRelationMapper.getGoodsNum(columnId);
    }
}
