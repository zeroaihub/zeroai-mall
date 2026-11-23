package com.zeroai.ai.mall.tool;

import com.zeroai.ai.mall.event.CartAdded;
import com.zeroai.ai.mall.event.GuideUiEvents;
import com.zeroai.domain.api.cart.entity.Cart;
import com.zeroai.domain.api.cart.service.ICartService;
import com.zeroai.util.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 加购工具测试。
 * <p>
 * 断言重点：身份必须由构造期绑定、加购走购物车域既有入口（不自己写库）、
 * 成功要下发卡片事件、失败要回传可读文案而不是堆栈。
 */
class AddToCartToolTest {

    @Test
    @DisplayName("未登录不允许创建加购工具")
    void rejectsNullUserId() {
        ICartService cartService = mock(ICartService.class);
        assertThrows(IllegalArgumentException.class, () -> new AddToCartTool(cartService, null, null));
    }

    @Test
    @DisplayName("缺少 goodsId 时直接返回提示，不触碰数据库")
    void missingGoodsIdReturnsHint() {
        ICartService cartService = mock(ICartService.class);
        String result = new AddToCartTool(cartService, 100L, null).addToCart(null, 1);

        assertTrue(result.contains("MISSING_GOODS_ID"), result);
        verify(cartService, never()).addDefaultGoodsProduct(any(), anyLong());
    }

    @Test
    @DisplayName("加购走购物车域入口，成功后回传 cartId 并下发事件")
    void addSucceedsAndPublishesEvent() {
        ICartService cartService = mock(ICartService.class);
        Cart saved = new Cart();
        saved.setId(55L);
        saved.setGoodsId(9L);
        saved.setGoodsName("测试商品");
        saved.setPrice(new BigDecimal("19.90"));
        saved.setNumber(2);
        saved.setPicUrl("http://img/9.png");
        when(cartService.list(
                org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<Cart>>any()))
                .thenReturn(List.of(saved));

        AtomicReference<CartAdded> pushed = new AtomicReference<>();
        GuideUiEvents events = new GuideUiEvents() {
            @Override
            public void onCartAdded(CartAdded added) {
                pushed.set(added);
            }
        };

        String result = new AddToCartTool(cartService, 100L, events).addToCart(9L, 2);

        verify(cartService).addDefaultGoodsProduct(any(), eq(100L));
        assertTrue(result.contains("\"added\":true"), result);
        assertTrue(result.contains("\"cartId\":55"), result);
        assertTrue(result.contains("\"number\":2"), result);
        assertNotNull(pushed.get(), "加购成功必须下发事件，前端才能把卡片标成已加入");
        assertEquals(55L, pushed.get().cartId());
    }

    @Test
    @DisplayName("数量越界时夹到 1..99，不把非法值透给购物车域")
    void clampsNumber() {
        ICartService cartService = mock(ICartService.class);
        Cart saved = new Cart();
        saved.setId(1L);
        saved.setGoodsId(9L);
        saved.setNumber(99);
        when(cartService.list(
                org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<Cart>>any()))
                .thenReturn(List.of(saved));

        new AddToCartTool(cartService, 100L, null).addToCart(9L, 1000);

        verify(cartService).addDefaultGoodsProduct(
                org.mockito.ArgumentMatchers.argThat(cart -> Integer.valueOf(99).equals(cart.getNumber())),
                eq(100L));
    }

    @Test
    @DisplayName("购物车域拒绝时回传可读文案，不泄露内部信息")
    void businessRejectionIsReadable() {
        ICartService cartService = mock(ICartService.class);
        doThrow(new BusinessException("商品库存不足"))
                .when(cartService).addDefaultGoodsProduct(any(), anyLong());

        String result = new AddToCartTool(cartService, 100L, null).addToCart(9L, 1);

        assertTrue(result.contains("ADD_TO_CART_REJECTED"), result);
        assertTrue(result.contains("库存不足"), result);
        assertFalse(result.contains("at com."), result);
    }
}
