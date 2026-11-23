package com.zeroai.domain.cart.support;

import com.zeroai.domain.api.common.MybatisPlusTableInfoTestHelper;

import com.zeroai.domain.api.cart.entity.Cart;
import com.zeroai.domain.api.goods.entity.Goods;
import com.zeroai.domain.api.goods.entity.GoodsProduct;
import com.zeroai.domain.api.cart.mapper.CartMapper;
import com.zeroai.domain.api.common.TradeLockSupport;
import com.zeroai.util.enums.ReturnCodeEnum;
import com.zeroai.util.exception.BusinessException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class CartWriteSupportTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisPlusTableInfoTestHelper.init(Cart.class);
    }

    @Mock
    private CartMapper cartMapper;
    @Mock
    private CartValidationSupport cartValidationSupport;
    @Mock
    private TradeLockSupport tradeLockSupport;
    @InjectMocks
    private CartWriteSupport cartWriteSupport;

    @Test
    void changeNumUpdatesSynchronouslyWhenStockEnough() {
        Cart cart = new Cart();
        cart.setId(1L);
        cart.setUserId(9);
        cart.setGoodsId(2L);
        cart.setProductId(3L);
        GoodsProduct product = new GoodsProduct();
        product.setGoodsId(2L);
        product.setNumber(10);

        when(cartMapper.selectById(1L)).thenReturn(cart);
        when(cartValidationSupport.requireProduct(3L, 2L)).thenReturn(product);
        when(cartMapper.updateById(any(Cart.class))).thenReturn(1);
        when(tradeLockSupport.executeWithLock(anyString(), eq(2), any(), any()))
                .thenAnswer(invocation -> invocation.<java.util.function.Supplier<Boolean>>getArgument(3).get());

        assertTrue(cartWriteSupport.changeNum(1L, 4, 9L));

        verify(cartMapper).updateById(any(Cart.class));
        verify(tradeLockSupport).executeWithLock(contains("9"), eq(2), any(), any());
    }

    @Test
    void changeNumRejectsOtherUsersCart() {
        Cart cart = new Cart();
        cart.setId(1L);
        // 购物车属于 8 号用户，却用 9 号身份去改：必须拒绝，否则改数量就是越权写
        cart.setUserId(8);
        when(cartMapper.selectById(1L)).thenReturn(cart);

        assertThrows(BusinessException.class, () -> cartWriteSupport.changeNum(1L, 4, 9L));

        verify(cartMapper, never()).updateById(any(Cart.class));
    }

    @Test
    void addThrowsWhenLockFails() {
        Cart cart = new Cart();
        cart.setGoodsId(2L);
        cart.setProductId(3L);
        cart.setNumber(1);
        Goods goods = new Goods();
        goods.setId(2L);
        GoodsProduct product = new GoodsProduct();
        product.setId(3L);
        product.setGoodsId(2L);
        product.setNumber(5);

        when(cartValidationSupport.requireOnSaleGoods(2L)).thenReturn(goods);
        when(cartValidationSupport.requireProduct(3L, 2L)).thenReturn(product);
        doThrow(new BusinessException(ReturnCodeEnum.ERROR, "购物车操作频繁，请稍后重试"))
                .when(tradeLockSupport)
                .runWithLock(anyString(), eq(2), any(), any());

        BusinessException exception = assertThrows(BusinessException.class, () -> cartWriteSupport.add(cart, 9L));

        assertEquals(ReturnCodeEnum.ERROR.getCode(), exception.getCode());
        verify(cartMapper, never()).insert(any(Cart.class));
    }

    @Test
    void updateCheckedUpdatesCartUnderUserLock() {
        when(cartMapper.update(any(), any())).thenReturn(1);
        when(tradeLockSupport.executeWithLock(anyString(), eq(2), any(), any()))
                .thenAnswer(invocation -> invocation.<java.util.function.Supplier<Boolean>>getArgument(3).get());

        assertTrue(cartWriteSupport.updateChecked(1L, true, 9L));

        verify(cartMapper).update(any(), any());
        verify(tradeLockSupport).executeWithLock(contains("9"), eq(2), any(), any());
    }

    @Test
    void updateCheckedIsIdempotentWhenValueUnchanged() {
        // MySQL 在「值没变」时受影响行数是 0，不能因此报「操作失败」：
        // 用户重复点同一个勾选状态是合法操作，报错会让人以为没生效
        when(cartMapper.update(any(), any())).thenReturn(0);
        Cart existing = new Cart();
        existing.setId(1L);
        existing.setUserId(9);
        when(cartMapper.selectById(1L)).thenReturn(existing);
        when(tradeLockSupport.executeWithLock(anyString(), eq(2), any(), any()))
                .thenAnswer(invocation -> invocation.<java.util.function.Supplier<Boolean>>getArgument(3).get());

        assertTrue(cartWriteSupport.updateChecked(1L, true, 9L));
    }

    @Test
    void updateCheckedReturnsFalseWhenCartBelongsToOthers() {
        when(cartMapper.update(any(), any())).thenReturn(0);
        Cart existing = new Cart();
        existing.setId(1L);
        // 别人的购物车：即便受影响行数为 0 也不能当成功
        existing.setUserId(8);
        when(cartMapper.selectById(1L)).thenReturn(existing);
        when(tradeLockSupport.executeWithLock(anyString(), eq(2), any(), any()))
                .thenAnswer(invocation -> invocation.<java.util.function.Supplier<Boolean>>getArgument(3).get());

        assertFalse(cartWriteSupport.updateChecked(1L, true, 9L));
    }
}
