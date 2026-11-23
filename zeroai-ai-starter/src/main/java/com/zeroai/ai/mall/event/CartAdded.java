package com.zeroai.ai.mall.event;

import java.math.BigDecimal;

/**
 * 加购成功事件载荷。
 * <p>
 * 下发时机：{@code add_to_cart} 工具写库成功之后。前端用它把对应商品卡标成「已加入购物车」，
 * 并提供「去下单」的下一步，避免用户重复点加购按钮。
 * <p>
 * 不带库存、成本价等内部字段；cartId 只用于前端去重与后续勾选，不构成越权入口
 * （购物车读写接口本身仍按登录用户过滤）。
 *
 * @param cartId    购物车行 ID
 * @param goodsId   商品 ID
 * @param goodsName 商品名称
 * @param unitPrice 加购时的单价快照
 * @param number    该商品在购物车中的目标数量
 * @param picUrl    主图地址，可为空
 */
public record CartAdded(Long cartId, Long goodsId, String goodsName,
                        BigDecimal unitPrice, Integer number, String picUrl) {
}
