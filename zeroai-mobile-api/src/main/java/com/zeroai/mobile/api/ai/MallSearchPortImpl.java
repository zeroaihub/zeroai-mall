package com.zeroai.mobile.api.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zeroai.ai.mall.port.MallSearchPort;
import com.zeroai.common.model.request.SearchRequestVO;
import com.zeroai.common.model.response.SearchGoodsItemResVO;
import com.zeroai.mobile.api.service.SearchApplicationService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * 检索端口实现：把 AI 通道的搜索请求转给既有 H5 检索链路。
 * <p>
 * 只做参数转换与字段裁剪，不新增任何查询语义，保证「AI 搜到的」与「页面搜到的」完全一致。
 * <p>
 * 关于 {@code memberId}：这里刻意传 {@code null}。{@code searchResult} 在最后会异步写搜索历史，
 * 那是面向「用户主动搜索」的行为记录。AI 代搜不应污染用户的历史画像，
 * 传 null 可让下游跳过落库，同时保证本次调用是纯读。
 */
@Component
public class MallSearchPortImpl implements MallSearchPort {

    private final SearchApplicationService searchApplicationService;

    /**
     * @param searchApplicationService 既有检索应用服务
     */
    public MallSearchPortImpl(SearchApplicationService searchApplicationService) {
        this.searchApplicationService = searchApplicationService;
    }

    /**
     * 按关键词检索在售商品。
     *
     * @param keyword 检索词
     * @param limit   返回条数上限
     * @return 商品摘要列表；检索异常或空结果时返回空列表
     */
    @Override
    public List<GoodsBrief> search(String keyword, int limit) {
        SearchRequestVO request = new SearchRequestVO();
        request.setKeyword(keyword);

        Page<SearchRequestVO> page = new Page<>(1, limit);
        List<SearchGoodsItemResVO> items;
        try {
            items = searchApplicationService.searchResult(request, page, null);
        } catch (IOException e) {
            // 交给工具层统一转成可读的降级提示，这里不再包装语义
            throw new IllegalStateException("商品检索失败", e);
        }
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        return items.stream()
                .map(item -> new GoodsBrief(item.getId(), item.getName(),
                        item.getRetailPrice(), item.getActualSales(), item.getPicUrl()))
                .toList();
    }
}
