package com.zeroai.domain.goods.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zeroai.domain.api.goods.entity.Column;
import com.zeroai.domain.api.goods.mapper.ColumnMapper;
import com.zeroai.domain.api.goods.service.IColumnService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 首页栏目配置 服务实现类
 *
 * @since 2020-10-10
 */
@Service
@AllArgsConstructor
public class ColumnServiceImpl extends ServiceImpl<ColumnMapper, Column> implements IColumnService {

    private ColumnMapper columnMapper;

    @Override
    public IPage<Column> listPage(Page<Column> page, Column column) {
        return columnMapper.selectColumnListPage(page, column);
    }
}
