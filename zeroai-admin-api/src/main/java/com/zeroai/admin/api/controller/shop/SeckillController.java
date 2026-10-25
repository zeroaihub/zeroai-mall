package com.zeroai.admin.api.controller.shop;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zeroai.common.base.controller.BaseController;
import com.zeroai.domain.api.promotion.entity.SeckillActivity;
import com.zeroai.domain.api.promotion.entity.SeckillSku;
import com.zeroai.domain.api.promotion.request.SeckillActivitySaveReqVO;
import com.zeroai.domain.api.promotion.response.SeckillActivityDetailResVO;
import com.zeroai.domain.api.promotion.response.SeckillActivityListItemResVO;
import com.zeroai.domain.api.promotion.service.ISeckillActivityService;
import com.zeroai.domain.api.promotion.service.ISeckillSkuService;
import com.zeroai.util.util.R;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 后台秒杀活动管理接口。
 * 负责活动配置、发布上下架和库存预热；秒杀下单链路不在后台接口中执行。
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("shop/seckill")
public class SeckillController extends BaseController {

    private final ISeckillActivityService seckillActivityService;
    private final ISeckillSkuService seckillSkuService;

    /**
     * 分页查询秒杀活动。
     *
     * @param query 查询条件
     * @return 秒杀活动分页
     */
    @PreAuthorize("@ss.hasPermi('shop:seckill:list')")
    @GetMapping("list")
    public R<IPage<SeckillActivityListItemResVO>> list(SeckillActivity query) {
        Page<SeckillActivity> page = getPage();
        IPage<SeckillActivity> result = seckillActivityService.listPage(page, query);
        Page<SeckillActivityListItemResVO> resPage =
                new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        resPage.setRecords(BeanUtil.copyToList(result.getRecords(), SeckillActivityListItemResVO.class));
        log.info("后台查询秒杀活动列表完成, total={}", result.getTotal());
        return R.success(resPage);
    }

    /**
     * 新增秒杀活动。
     *
     * @param reqVO 活动保存请求
     * @return 处理结果
     */
    @PreAuthorize("@ss.hasPermi('shop:seckill:add')")
    @PostMapping
    public R<Boolean> add(@Validated @RequestBody SeckillActivitySaveReqVO reqVO) {
        seckillActivityService.saveActivity(reqVO);
        log.info("后台新增秒杀活动完成, activityId={}, name={}",
                reqVO.getActivity().getId(), reqVO.getActivity().getName());
        return R.success();
    }

    /**
     * 更新秒杀活动。
     *
     * @param reqVO 活动保存请求
     * @return 处理结果
     */
    @PreAuthorize("@ss.hasPermi('shop:seckill:update')")
    @PutMapping
    public R<Boolean> update(@Validated @RequestBody SeckillActivitySaveReqVO reqVO) {
        seckillActivityService.updateActivity(reqVO);
        log.info("后台更新秒杀活动完成, activityId={}, name={}",
                reqVO.getActivity().getId(), reqVO.getActivity().getName());
        return R.success();
    }

    /**
     * 查询秒杀活动详情。
     *
     * @param activityId 活动 ID
     * @return 活动详情
     */
    @PreAuthorize("@ss.hasPermi('shop:seckill:info')")
    @GetMapping("{activityId}")
    public R<SeckillActivityDetailResVO> detail(@PathVariable Long activityId) {
        SeckillActivityDetailResVO result = seckillActivityService.detail(activityId);
        return R.success(result);
    }

    /**
     * 发布秒杀活动。
     *
     * @param activityId 活动 ID
     * @return 处理结果
     */
    @PreAuthorize("@ss.hasPermi('shop:seckill:update')")
    @PostMapping("{activityId}/publish")
    public R<Boolean> publish(@PathVariable Long activityId) {
        seckillActivityService.publish(activityId);
        log.info("后台发布秒杀活动完成, activityId={}", activityId);
        return R.success();
    }

    /**
     * 下架秒杀活动。
     *
     * @param activityId 活动 ID
     * @return 处理结果
     */
    @PreAuthorize("@ss.hasPermi('shop:seckill:update')")
    @PostMapping("{activityId}/offline")
    public R<Boolean> offline(@PathVariable Long activityId) {
        seckillActivityService.offline(activityId);
        log.info("后台下架秒杀活动完成, activityId={}", activityId);
        return R.success();
    }

    /**
     * 预热活动库存到 Redis。
     *
     * @param activityId 活动 ID
     * @return 处理结果
     */
    @PreAuthorize("@ss.hasPermi('shop:seckill:update')")
    @PostMapping("{activityId}/preheat")
    public R<Boolean> preheat(@PathVariable Long activityId) {
        seckillActivityService.preheat(activityId);
        log.info("后台预热秒杀活动库存完成, activityId={}", activityId);
        return R.success();
    }

    /**
     * 删除秒杀活动。
     *
     * @param ids 活动 ID 列表
     * @return 处理结果
     */
    @PreAuthorize("@ss.hasPermi('shop:seckill:delete')")
    @DeleteMapping("{ids}")
    public R<Boolean> delete(@PathVariable List<Long> ids) {
        boolean removed = seckillActivityService.removeByIds(ids);
        if (removed) {
            seckillSkuService.remove(Wrappers.lambdaQuery(SeckillSku.class).in(SeckillSku::getActivityId, ids));
        }
        log.info("后台删除秒杀活动完成, ids={}, result={}", ids, removed);
        return R.result(removed);
    }
}
