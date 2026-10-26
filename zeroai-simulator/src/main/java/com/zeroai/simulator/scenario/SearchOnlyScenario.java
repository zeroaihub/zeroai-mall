package com.zeroai.simulator.scenario;

import com.zeroai.simulator.auth.VirtualUser;
import com.zeroai.simulator.client.AdminApi;
import com.zeroai.simulator.client.ApiResponse;
import com.zeroai.simulator.client.MobileApi;
import com.zeroai.simulator.config.SimConfig;
import com.zeroai.simulator.metric.Metrics;

import java.util.List;

/**
 * 纯搜索场景：搜索列表 + 一半概率进详情。
 * <p>
 * 用于单独压 Elasticsearch 查询链路。它不写业务数据，可以放心拉到最高 QPS 找拐点，
 * 是判断「搜索到底能扛多少」最快的手段。
 */
public final class SearchOnlyScenario extends AbstractScenario {

    public SearchOnlyScenario(SimConfig cfg, MobileApi mobile, AdminApi admin, Metrics metrics) {
        super(cfg, mobile, admin, metrics);
    }

    @Override
    public String name() {
        return "search_only";
    }

    @Override
    public boolean run(VirtualUser user) {
        String keyword = pick(cfg.keywords());
        int page = 1 + random.nextInt(3);
        ApiResponse search = mobile.search(user.token(), keyword, page);
        if (!search.ok()) {
            return false;
        }
        List<Long> goodsIds = MobileApi.extractGoodsIds(search);
        if (goodsIds.isEmpty()) {
            abort("chain.search_empty");
            return false;
        }
        // 一半用户会点进详情，另一半滑走
        if (random.nextBoolean()) {
            return mobile.goodsDetail(pick(goodsIds)).ok();
        }
        return true;
    }
}
