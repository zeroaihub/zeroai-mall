package com.zeroai.ai.mall.event;

import java.math.BigDecimal;
import java.util.List;

/**
 * 下单前「挑选商品」事件载荷：把用户购物车整份下发给前端弹层。
 * <p>
 * 为什么需要它：{@code propose_order} 只认购物车里 {@code checked=true} 的行，
 * 而勾选状态原本只能在购物车页操作，等于「在对话里说要下单」之后还得跳出去勾一遍再回来。
 * 这里改成：服务端把购物车弹到对话内，用户勾完直接生成提议，链路不再中断。
 * <p>
 * 地址随事件一起下发，是因为生成提议必须带收货地址；弹层需要把它显示出来让用户知道
 * 这一单寄到哪里，缺地址时也能立刻提示去新增，而不是等点了按钮才报错。
 *
 * @param addressId    收货地址 ID，已按「默认地址优先」解析；为空表示用户还没有任何地址
 * @param addressText  地址摘要（收件人 + 脱敏手机号 + 省市区），为空表示没有可用地址
 * @param userCouponId 用户优惠券 ID，可为空
 * @param items        购物车全部商品，含勾选状态，前端据此渲染初始勾选
 * @param totalNumber  购物车商品总件数，仅用于展示
 */
public record CartSelection(Long addressId,
                            String addressText,
                            Long userCouponId,
                            List<Item> items,
                            int totalNumber) {

    /**
     * 购物车行。
     *
     * @param cartId     购物车 ID，生成提议时回传
     * @param goodsId    商品 ID
     * @param productId  货品 ID
     * @param goodsName  商品名称
     * @param unitPrice  单价快照
     * @param number     数量
     * @param checked    当前是否勾选
     * @param picUrl     主图地址，可为空
     */
    public record Item(Long cartId, Long goodsId, Long productId, String goodsName,
                       BigDecimal unitPrice, Integer number, boolean checked, String picUrl) {
    }
}
