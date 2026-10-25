package com.zeroai.domain.goods.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zeroai.domain.api.goods.entity.SearchHistory;
import com.zeroai.domain.api.goods.mapper.SearchHistoryMapper;
import com.zeroai.domain.api.goods.service.ISearchHistoryService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 搜索历史表 服务实现类
 *
 * @since 2020-09-23
 */
@Service
@AllArgsConstructor
public class SearchHistoryServiceImpl extends ServiceImpl<SearchHistoryMapper, SearchHistory> implements ISearchHistoryService {

    private SearchHistoryMapper searchHistoryMapper;

    @Override
    public List<SearchHistory> selectList(Long userId) {
        return searchHistoryMapper.selectSeachHistoryList(userId);
    }

    @Override
    public List<SearchHistory> selectHostList() {
        return searchHistoryMapper.selectHostList();
    }
}
