package com.zeroai.domain.api.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zeroai.domain.api.promotion.entity.ShopCoupon;
import com.zeroai.domain.api.promotion.request.ShopCouponReqVO;
import com.zeroai.domain.api.promotion.response.MemberCouponResVO;
import com.zeroai.domain.api.promotion.response.ShopCouponManageResVO;
import com.zeroai.domain.api.promotion.response.ShopCouponResVO;

/**
 * @author Administrator
 * @description 针对表【shop_coupon(优惠券)】的数据库操作Mapper
 * @createDate 2024-06-06 10:26:11
 * @Entity generator.domain.ShopCoupon
 */
public interface ShopCouponMapper extends BaseMapper<ShopCoupon> {

    IPage<ShopCouponManageResVO> selectCouponListPage(Page<ShopCoupon> page, ShopCouponReqVO reqVO);

    IPage<ShopCouponResVO> fontList(Page<ShopCoupon> page);

    IPage<MemberCouponResVO> myList(Page<ShopCoupon> page, Long userId);

    int updateReceiveNum(Integer couponId);
}


