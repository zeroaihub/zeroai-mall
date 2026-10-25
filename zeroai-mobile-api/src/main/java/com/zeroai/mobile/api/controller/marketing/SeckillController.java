package com.zeroai.mobile.api.controller.marketing;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zeroai.common.base.controller.BaseController;
import com.zeroai.domain.api.promotion.entity.SeckillActivity;
import com.zeroai.domain.api.promotion.enums.SeckillActivityStatusEnum;
import com.zeroai.domain.api.promotion.request.SeckillSubmitReqVO;
import com.zeroai.domain.api.promotion.response.SeckillActivityDetailResVO;
import com.zeroai.domain.api.promotion.response.SeckillActivityListItemResVO;
import com.zeroai.domain.api.promotion.response.SeckillResultResVO;
import com.zeroai.domain.api.promotion.response.SeckillSubmitResVO;
import com.zeroai.domain.api.promotion.response.SeckillTokenResVO;
import com.zeroai.domain.api.promotion.service.ISeckillActivityService;
import com.zeroai.domain.promotion.support.seckill.SeckillResultSupport;
import com.zeroai.domain.promotion.support.seckill.SeckillTokenSupport;
import com.zeroai.domain.trade.support.seckill.SeckillOrderSubmitSupport;
import com.zeroai.mobile.framework.security.util.MobileSecurityUtils;
import com.zeroai.util.util.R;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 移动端秒杀接口。
 * 对外只暴露活动查询、短期 token 签发、提交和结果轮询，核心并发控制下沉到领域支撑服务。
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("seckill")
public class SeckillController extends BaseController {

    private final ISeckillActivityService seckillActivityService;
    private final SeckillTokenSupport seckillTokenSupport;
    private final SeckillOrderSubmitSupport seckillOrderSubmitSupport;
    private final SeckillResultSupport seckillResultSupport;

    /**
     * 分页查询秒杀活动。
     *
     * @return 秒杀活动分页
     */
    @GetMapping("list")
    public R<IPage<SeckillActivityListItemResVO>> list() {
        Page<SeckillActivity> page = getPage();
        SeckillActivity query = new SeckillActivity();
        query.setStatus(SeckillActivityStatusEnum.PUBLISHED.getStatus());
        log.info("查询秒杀活动列表开始, pageNum={}, pageSize={}", page.getCurrent(), page.getSize());
        IPage<SeckillActivity> result = seckillActivityService.listPage(page, query);
        Page<SeckillActivityListItemResVO> resPage =
                new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        resPage.setRecords(BeanUtil.copyToList(result.getRecords(), SeckillActivityListItemResVO.class));
        log.info("查询秒杀活动列表完成, total={}", result.getTotal());
        return R.success(resPage);
    }

    /**
     * 查询秒杀活动详情。
     *
     * @param activityId 活动 ID
     * @return 活动详情
     */
    @GetMapping("detail/{activityId}")
    public R<SeckillActivityDetailResVO> detail(@PathVariable Long activityId) {
        log.info("查询秒杀活动详情开始, activityId={}", activityId);
        SeckillActivityDetailResVO result = seckillActivityService.detail(activityId);
        log.info("查询秒杀活动详情完成, activityId={}, skuCount={}", activityId, result.getSkuList().size());
        return R.success(result);
    }

    /**
     * 签发秒杀提交 token。
     *
     * @param activitySkuId 活动 SKU ID
     * @return 秒杀 token
     */
    @GetMapping("token/{activitySkuId}")
    public R<SeckillTokenResVO> token(@PathVariable Long activitySkuId) {
        Long userId = MobileSecurityUtils.getUserId();
        String token = seckillTokenSupport.issueToken(activitySkuId, userId);
        log.info("签发秒杀token完成, userId={}, activitySkuId={}", userId, activitySkuId);
        return R.success(new SeckillTokenResVO(token, 60));
    }

    /**
     * 提交秒杀订单。
     *
     * @param reqVO 秒杀提交请求
     * @return 秒杀提交结果
     */
    @PostMapping("submit")
    public R<SeckillSubmitResVO> submit(@RequestBody SeckillSubmitReqVO reqVO) {
        Long userId = MobileSecurityUtils.getUserId();
        log.info("提交秒杀订单开始, userId={}, activitySkuId={}, number={}, addressId={}",
                userId, reqVO.getActivitySkuId(), reqVO.getNumber(), reqVO.getAddressId());
        SeckillSubmitResVO result = seckillOrderSubmitSupport.submit(reqVO, userId);
        log.info("提交秒杀订单完成, userId={}, activitySkuId={}, orderSn={}, status={}",
                userId, reqVO.getActivitySkuId(), result.getOrderSn(), result.getStatus());
        return R.success(result);
    }

    /**
     * 查询秒杀订单处理结果。
     *
     * @param orderSn 订单号
     * @return 秒杀结果
     */
    @GetMapping("result/{orderSn}")
    public R<SeckillResultResVO> result(@PathVariable String orderSn) {
        SeckillResultResVO result = seckillResultSupport.getResult(orderSn);
        log.info("查询秒杀结果完成, userId={}, orderSn={}, status={}",
                MobileSecurityUtils.getUserId(), orderSn, result.getStatus());
        return R.success(result);
    }
}
