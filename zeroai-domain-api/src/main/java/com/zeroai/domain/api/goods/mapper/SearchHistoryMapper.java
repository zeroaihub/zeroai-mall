package com.zeroai.domain.api.goods.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zeroai.domain.api.goods.entity.SearchHistory;

import java.util.List;

/**
 * 搜索历史表 Mapper 接口
 *
 * @since 2020-09-23
 */
public interface SearchHistoryMapper extends BaseMapper<SearchHistory> {

    List<SearchHistory> selectSeachHistoryList(Long memberId);

    List<SearchHistory> selectHostList();
}
