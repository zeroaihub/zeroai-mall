package com.zeroai.domain.promotion.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zeroai.data.redis.manager.RedisCache;
import com.zeroai.domain.api.goods.entity.Goods;
import com.zeroai.domain.api.goods.entity.GoodsProduct;
import com.zeroai.domain.api.goods.service.IGoodsProductService;
import com.zeroai.domain.api.goods.service.IGoodsService;
import com.zeroai.domain.api.promotion.entity.SeckillActivity;
import com.zeroai.domain.api.promotion.entity.SeckillSku;
import com.zeroai.domain.api.promotion.enums.SeckillActivityStatusEnum;
import com.zeroai.domain.api.promotion.mapper.SeckillActivityMapper;
import com.zeroai.domain.api.promotion.request.SeckillActivitySaveReqVO;
import com.zeroai.domain.api.promotion.response.SeckillActivityDetailResVO;
import com.zeroai.domain.api.promotion.response.SeckillActivityListItemResVO;
import com.zeroai.domain.api.promotion.response.SeckillSkuResVO;
import com.zeroai.domain.api.promotion.service.ISeckillActivityService;
import com.zeroai.domain.api.promotion.service.ISeckillSkuService;
import com.zeroai.domain.promotion.support.seckill.SeckillRedisKeySupport;
import com.zeroai.util.enums.ReturnCodeEnum;
import com.zeroai.util.exception.BusinessException;
import lombok.AllArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 秒杀活动服务实现。
 * 负责后台活动配置、活动 SKU 维护和 Redis 库存预热；下单链路的并发过滤不放在这里，统一由交易域秒杀提交支撑服务编排。
 */
@Service
@AllArgsConstructor
public class SeckillActivityServiceImpl extends ServiceImpl<SeckillActivityMapper, SeckillActivity>
        implements ISeckillActivityService {

    private final ISeckillSkuService seckillSkuService;
    private final RedisCache redisCache;
    private final IGoodsService goodsService;
    private final IGoodsProductService goodsProductService;

    /**
     * 分页查询秒杀活动。
     *
     * @param page 分页
     * @param query 查询条件
     * @return 活动分页
     */
    @Override
    public IPage<SeckillActivity> listPage(Page<SeckillActivity> page, SeckillActivity query) {
        String name = query == null ? null : query.getName();
        Integer status = query == null ? null : query.getStatus();
        return page(page, Wrappers.lambdaQuery(SeckillActivity.class)
                .like(StringUtils.isNotBlank(name), SeckillActivity::getName, name)
                .eq(status != null, SeckillActivity::getStatus, status)
                .orderByDesc(SeckillActivity::getCreateTime));
    }

    /**
     * 保存活动和活动 SKU。
     * 活动主表和 SKU 明细必须在一个事务内提交，避免活动存在但没有可抢商品。
     *
     * @param reqVO 保存请求
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveActivity(SeckillActivitySaveReqVO reqVO) {
        validateSaveRequest(reqVO, false);
        SeckillActivity activity = reqVO.getActivity();
        Date now = new Date();
        activity.setId(null);
        activity.setStatus(defaultStatus(activity.getStatus()));
        activity.setCreateTime(now);
        activity.setUpdateTime(now);
        activity.setDelFlag(Boolean.FALSE);
        save(activity);
        saveSkuList(activity.getId(), reqVO.getSkuList(), now);
    }

    /**
     * 更新活动和活动 SKU。
     * 采用先删后插的方式保持维护成本低，活动 SKU 已产生订单后不建议直接修改，后续可拆出审核流。
     *
     * @param reqVO 保存请求
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateActivity(SeckillActivitySaveReqVO reqVO) {
        validateSaveRequest(reqVO, true);
        SeckillActivity activity = reqVO.getActivity();
        activity.setUpdateTime(new Date());
        if (!updateById(activity)) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_ERROR, "秒杀活动不存在");
        }
        // 活动 SKU 使用 activity_id + product_id 唯一键，逻辑删除会继续占用唯一键，重建 SKU 列表必须物理删除旧记录。
        seckillSkuService.physicalDeleteByActivityId(activity.getId());
        saveSkuList(activity.getId(), reqVO.getSkuList(), new Date());
    }

    /**
     * 查询活动详情。
     *
     * @param activityId 活动 ID
     * @return 活动详情
     */
    @Override
    public SeckillActivityDetailResVO detail(Long activityId) {
        if (activityId == null) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_MISS_ERROR);
        }
        SeckillActivity activity = getById(activityId);
        if (activity == null) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_ERROR, "秒杀活动不存在");
        }
        SeckillActivityDetailResVO resVO = new SeckillActivityDetailResVO();
        resVO.setActivity(BeanUtil.copyProperties(activity, SeckillActivityListItemResVO.class));
        List<SeckillSkuResVO> skuResList = BeanUtil.copyToList(listSkuByActivityId(activityId), SeckillSkuResVO.class);
        fillSkuGoodsInfo(skuResList);
        resVO.setSkuList(skuResList);
        return resVO;
    }

    /**
     * 补齐秒杀 SKU 展示信息。
     * 秒杀 SKU 表只保存活动价格和库存三态，移动端详情需要商品名称、商品主图和货品图，因此这里批量查询避免 N+1。
     *
     * @param skuList 秒杀 SKU 响应列表
     */
    private void fillSkuGoodsInfo(List<SeckillSkuResVO> skuList) {
        if (CollectionUtils.isEmpty(skuList)) {
            return;
        }
        Map<Long, Goods> goodsMap = indexGoodsById(skuList);
        Map<Long, GoodsProduct> productMap = indexProductsById(skuList);
        for (SeckillSkuResVO sku : skuList) {
            Goods goods = goodsMap.get(sku.getGoodsId());
            if (goods != null) {
                sku.setGoodsName(goods.getName());
                sku.setGoodsPicUrl(goods.getPicUrl());
            }
            GoodsProduct product = productMap.get(sku.getProductId());
            if (product != null) {
                sku.setProductPicUrl(product.getUrl());
                sku.setSpecifications(product.getSpecifications());
            }
            sku.setPicUrl(StringUtils.defaultIfBlank(sku.getProductPicUrl(), sku.getGoodsPicUrl()));
        }
    }

    /**
     * 按商品 ID 构建商品索引。
     *
     * @param skuList 秒杀 SKU 响应列表
     * @return 商品 ID 到商品信息的映射
     */
    private Map<Long, Goods> indexGoodsById(List<SeckillSkuResVO> skuList) {
        List<Long> goodsIds = skuList.stream()
                .map(SeckillSkuResVO::getGoodsId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (CollectionUtils.isEmpty(goodsIds)) {
            return Collections.emptyMap();
        }
        List<Goods> goodsList = goodsService.selectGoodsByIds(goodsIds);
        if (CollectionUtils.isEmpty(goodsList)) {
            return Collections.emptyMap();
        }
        return goodsList.stream()
                .filter(goods -> goods != null && goods.getId() != null)
                .collect(Collectors.toMap(Goods::getId, goods -> goods, (left, right) -> left));
    }

    /**
     * 按货品 ID 构建货品索引。
     *
     * @param skuList 秒杀 SKU 响应列表
     * @return 货品 ID 到货品信息的映射
     */
    private Map<Long, GoodsProduct> indexProductsById(List<SeckillSkuResVO> skuList) {
        List<Long> productIds = skuList.stream()
                .map(SeckillSkuResVO::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (CollectionUtils.isEmpty(productIds)) {
            return Collections.emptyMap();
        }
        List<GoodsProduct> productList = goodsProductService.selectProductByIds(productIds);
        if (CollectionUtils.isEmpty(productList)) {
            return Collections.emptyMap();
        }
        return productList.stream()
                .filter(product -> product != null && product.getId() != null)
                .collect(Collectors.toMap(GoodsProduct::getId, product -> product, (left, right) -> left));
    }

    /**
     * 发布秒杀活动。
     *
     * @param activityId 活动 ID
     */
    @Override
    public void publish(Long activityId) {
        updateStatus(activityId, SeckillActivityStatusEnum.PUBLISHED);
    }

    /**
     * 下架秒杀活动。
     *
     * @param activityId 活动 ID
     */
    @Override
    public void offline(Long activityId) {
        updateStatus(activityId, SeckillActivityStatusEnum.OFFLINE);
    }

    /**
     * 预热活动库存到 Redis。
     * Redis 只作为秒杀入口漏斗，数据库活动库存仍是最终一致性兜底。
     *
     * @param activityId 活动 ID
     */
    @Override
    public void preheat(Long activityId) {
        SeckillActivity activity = getById(activityId);
        if (activity == null) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_ERROR, "秒杀活动不存在");
        }
        if (!Objects.equals(activity.getStatus(), SeckillActivityStatusEnum.PUBLISHED.getStatus())) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_ERROR, "秒杀活动未发布");
        }
        for (SeckillSku sku : listSkuByActivityId(activityId)) {
            if (Objects.equals(sku.getStatus(), SeckillActivityStatusEnum.PUBLISHED.getStatus())) {
                redisCache.setCacheObject(SeckillRedisKeySupport.stockKey(sku.getId()),
                        defaultNumber(sku.getAvailableStock()));
            }
        }
    }

    /**
     * 校验保存请求。
     *
     * @param reqVO 保存请求
     * @param requireId 是否要求活动 ID
     */
    private void validateSaveRequest(SeckillActivitySaveReqVO reqVO, boolean requireId) {
        if (reqVO == null || reqVO.getActivity() == null) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_MISS_ERROR);
        }
        if (requireId && reqVO.getActivity().getId() == null) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_MISS_ERROR);
        }
        if (StringUtils.isBlank(reqVO.getActivity().getName())) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_ERROR, "秒杀活动名称不能为空");
        }
        if (reqVO.getActivity().getStartTime() == null || reqVO.getActivity().getEndTime() == null
                || !reqVO.getActivity().getStartTime().before(reqVO.getActivity().getEndTime())) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_ERROR, "秒杀活动时间范围错误");
        }
        if (CollectionUtils.isEmpty(reqVO.getSkuList())) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_ERROR, "秒杀活动商品不能为空");
        }
        validateDistinctSkuProducts(reqVO.getSkuList());
    }

    /**
     * 校验同一秒杀活动内货品唯一。
     * 数据库通过 activity_id + product_id 唯一键兜底，这里提前拦截重复货品，避免批量保存阶段抛出底层唯一键异常。
     *
     * @param skuList 活动 SKU 列表
     */
    private void validateDistinctSkuProducts(List<SeckillSku> skuList) {
        Set<Long> productIds = new HashSet<>();
        for (SeckillSku sku : skuList) {
            if (sku == null || sku.getProductId() == null) {
                continue;
            }
            if (!productIds.add(sku.getProductId())) {
                throw new BusinessException(ReturnCodeEnum.PARAMETER_ERROR, "秒杀活动货品不能重复");
            }
        }
    }

    /**
     * 保存活动 SKU 列表。
     *
     * @param activityId 活动 ID
     * @param skuList SKU 列表
     * @param now 当前时间
     */
    private void saveSkuList(Long activityId, List<SeckillSku> skuList, Date now) {
        for (SeckillSku sku : skuList) {
            validateSku(sku);
            sku.setId(null);
            sku.setActivityId(activityId);
            sku.setLockedStock(defaultNumber(sku.getLockedStock()));
            sku.setSoldStock(defaultNumber(sku.getSoldStock()));
            sku.setLimitCount(defaultLimitCount(sku.getLimitCount()));
            sku.setStatus(defaultStatus(sku.getStatus()));
            sku.setCreateTime(now);
            sku.setUpdateTime(now);
            sku.setDelFlag(Boolean.FALSE);
        }
        if (!seckillSkuService.saveBatch(skuList)) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_ERROR, "秒杀活动商品保存失败");
        }
    }

    /**
     * 校验活动 SKU。
     *
     * @param sku 活动 SKU
     */
    private void validateSku(SeckillSku sku) {
        if (sku == null || sku.getGoodsId() == null || sku.getProductId() == null || sku.getSeckillPrice() == null) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_ERROR, "秒杀活动商品参数不完整");
        }
        if (defaultNumber(sku.getAvailableStock()) <= 0) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_ERROR, "秒杀活动库存必须大于0");
        }
    }

    /**
     * 更新活动和活动 SKU 状态。
     *
     * @param activityId 活动 ID
     * @param targetStatus 目标状态
     */
    private void updateStatus(Long activityId, SeckillActivityStatusEnum targetStatus) {
        if (activityId == null) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_MISS_ERROR);
        }
        boolean updated = lambdaUpdate()
                .set(SeckillActivity::getStatus, targetStatus.getStatus())
                .set(SeckillActivity::getUpdateTime, new Date())
                .eq(SeckillActivity::getId, activityId)
                .update();
        if (!updated) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_ERROR, "秒杀活动不存在");
        }
        seckillSkuService.lambdaUpdate()
                .set(SeckillSku::getStatus, targetStatus.getStatus())
                .set(SeckillSku::getUpdateTime, new Date())
                .eq(SeckillSku::getActivityId, activityId)
                .update();
    }

    /**
     * 查询活动 SKU 列表。
     *
     * @param activityId 活动 ID
     * @return 活动 SKU 列表
     */
    private List<SeckillSku> listSkuByActivityId(Long activityId) {
        return seckillSkuService.list(Wrappers.lambdaQuery(SeckillSku.class)
                .eq(SeckillSku::getActivityId, activityId)
                .orderByAsc(SeckillSku::getId));
    }

    /**
     * 默认活动状态。
     *
     * @param status 当前状态
     * @return 非空状态
     */
    private Integer defaultStatus(Integer status) {
        return status == null ? SeckillActivityStatusEnum.DRAFT.getStatus() : status;
    }

    /**
     * 默认限购数量。
     *
     * @param limitCount 限购数量
     * @return 非空限购数量
     */
    private Integer defaultLimitCount(Integer limitCount) {
        return limitCount == null || limitCount <= 0 ? 1 : limitCount;
    }

    /**
     * 默认数值。
     *
     * @param number 数值
     * @return 非空数值
     */
    private Integer defaultNumber(Integer number) {
        return number == null ? 0 : number;
    }
}
