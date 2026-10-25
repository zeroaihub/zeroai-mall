package com.zeroai.domain.promotion.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zeroai.domain.api.promotion.entity.Diamond;
import com.zeroai.domain.api.promotion.mapper.DiamondMapper;
import com.zeroai.domain.api.promotion.service.IDiamondService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 首页金刚区配置 服务实现类
 *
 * @since 2020-10-10
 */
@Service
@AllArgsConstructor
public class DiamondServiceImpl extends ServiceImpl<DiamondMapper, Diamond> implements IDiamondService {

    private DiamondMapper diamondMapper;

    @Override
    public IPage<Diamond> listPage(Page<Diamond> page, Diamond diamond) {
        return diamondMapper.selectDiamondListPage(page, diamond);
    }
}
