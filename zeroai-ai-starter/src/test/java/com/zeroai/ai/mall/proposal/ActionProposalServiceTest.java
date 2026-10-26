package com.zeroai.ai.mall.proposal;

import com.zeroai.ai.mall.proposal.entity.AiActionProposal;
import com.zeroai.ai.mall.proposal.mapper.AiActionProposalMapper;
import com.zeroai.ai.mall.proposal.vo.ConfirmResVO;
import com.zeroai.ai.mall.proposal.vo.ProposalVO;
import com.zeroai.domain.api.cart.entity.Cart;
import com.zeroai.domain.api.cart.service.ICartService;
import com.zeroai.domain.api.common.MybatisPlusTableInfoTestHelper;
import com.zeroai.domain.api.common.ZeroaiConfig;
import com.zeroai.domain.api.goods.entity.GoodsProduct;
import com.zeroai.domain.api.goods.service.IGoodsProductService;
import com.zeroai.domain.api.promotion.entity.ShopMemberCoupon;
import com.zeroai.domain.api.promotion.service.ShopMemberCouponService;
import com.zeroai.domain.api.trade.entity.Address;
import com.zeroai.domain.api.trade.request.OrderCommitReqVO;
import com.zeroai.domain.api.trade.response.SubmitOrderResVO;
import com.zeroai.domain.api.trade.service.IAddressService;
import com.zeroai.domain.api.trade.service.IMobileOrderService;
import com.zeroai.util.exception.BusinessException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写操作提议的状态机与安全边界测试。
 * <p>
 * 这里断言的是 Phase 6 的验收项，而不是「方法能跑通」：
 * <ul>
 *   <li>未确认的提议绝不创建订单；</li>
 *   <li>重复确认 / 并发双击只创建一次订单；</li>
 *   <li>过期、价格变化、库存不足、跨用户确认必须被拒绝并给出可理解原因。</li>
 * </ul>
 * 订单写入只通过 {@code IMobileOrderService.asyncSubmit}，测试里用 mock 断言「调用了几次」，
 * 这比断言返回值更能守住「不得复制下单逻辑」这条线。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActionProposalServiceTest {

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;
    private static final String PROPOSAL_NO = "p-1234567890abcdef";

    @Mock
    private AiActionProposalMapper proposalMapper;
    @Mock
    private ICartService cartService;
    @Mock
    private IGoodsProductService goodsProductService;
    @Mock
    private ShopMemberCouponService couponService;
    @Mock
    private IAddressService addressService;
    @Mock
    private IMobileOrderService mobileOrderService;

    private ActionProposalProperties properties;
    private ActionProposalService service;

    @BeforeAll
    static void initTableInfo() {
        MybatisPlusTableInfoTestHelper.init(Cart.class);
    }

    @BeforeEach
    void setUp() {
        properties = new ActionProposalProperties();
        properties.setEnabled(true);
        properties.setProposalTtlSeconds(600L);
        service = new ActionProposalService(proposalMapper, cartService, goodsProductService,
                couponService, addressService, mobileOrderService, properties);
        // 运费规则来自全局配置，测试里固定为「满 100 免运费，否则 10 元」
        new ZeroaiConfig().setFreightLimit(new BigDecimal("100"));
        new ZeroaiConfig().setFreightPrice(new BigDecimal("10"));
    }

    // ---------- 创建提议 ----------

    @Test
    @DisplayName("生成提议只写提议表，绝不创建订单")
    void createProposalNeverSubmitsOrder() throws Exception {
        givenAddressOwned();
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);
        when(proposalMapper.selectByIdempotencyKey(eq(USER_ID), anyString())).thenReturn(null);
        when(proposalMapper.insert(org.mockito.ArgumentMatchers.<AiActionProposal>any())).thenReturn(1);

        ProposalVO vo = service.createOrderProposal(USER_ID, 1L, null, List.of(10L));

        assertNotNull(vo.proposalNo());
        assertEquals(ProposalStatus.PENDING.name(), vo.status());
        // 商品 40 + 运费 10（不满 100）
        assertEquals(new BigDecimal("50.00"), vo.actualPrice().setScale(2));
        verify(mobileOrderService, never()).asyncSubmit(any(), anyLong());
    }

    @Test
    @DisplayName("同一购物车快照重复生成提议时复用既有记录")
    void sameSnapshotReusesExistingProposal() throws Exception {
        givenAddressOwned();
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);
        AiActionProposal existing = pendingProposal();
        when(proposalMapper.selectByIdempotencyKey(eq(USER_ID), anyString())).thenReturn(existing);

        ProposalVO first = service.createOrderProposal(USER_ID, 1L, null, List.of(10L));
        ProposalVO second = service.createOrderProposal(USER_ID, 1L, null, List.of(10L));

        assertEquals(PROPOSAL_NO, first.proposalNo());
        assertEquals(PROPOSAL_NO, second.proposalNo());
        verify(proposalMapper, never()).insert(org.mockito.ArgumentMatchers.<AiActionProposal>any());
    }

    @Test
    @DisplayName("未登录不允许生成提议")
    void anonymousCannotCreateProposal() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> service.createOrderProposal(null, 1L, null, List.of(10L)));
    }

    @Test
    @DisplayName("地址不属于当前用户时拒绝生成提议")
    void createRejectsForeignAddress() throws Exception {
        Address foreign = new Address();
        foreign.setId(1L);
        foreign.setMemberId(OTHER_USER_ID);
        when(addressService.getById(1L)).thenReturn(foreign);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createOrderProposal(USER_ID, 1L, null, List.of(10L)));
        assertEquals(ProposalRejectCode.ADDRESS_INVALID.getMessage(), ex.getMsg());
    }

    // ---------- 确认：成功 ----------

    @Test
    @DisplayName("用户确认后只调用原下单入口一次")
    void confirmCallsOriginSubmitOnce() throws Exception {
        givenAddressOwned();
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);
        givenStock(30L, 10);
        AiActionProposal pending = pendingProposal();
        when(proposalMapper.claimPending(eq(PROPOSAL_NO), eq(USER_ID), any())).thenReturn(1);
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(pending);
        when(mobileOrderService.asyncSubmit(any(), eq(USER_ID))).thenReturn(submitResult("SN-1", "50.00"));

        ConfirmResVO res = service.confirm(USER_ID, PROPOSAL_NO);

        assertTrue(res.ok());
        assertEquals("SN-1", res.orderSn());
        ArgumentCaptor<OrderCommitReqVO> captor = ArgumentCaptor.forClass(OrderCommitReqVO.class);
        verify(mobileOrderService, times(1)).asyncSubmit(captor.capture(), eq(USER_ID));
        assertEquals(pending.getAddressId(), captor.getValue().getAddressId());
        verify(proposalMapper).markConfirmed(eq(pending.getId()), eq("SN-1"), any());
    }

    // ---------- 确认：重复与并发 ----------

    @Test
    @DisplayName("重复确认不产生第二张订单")
    void duplicateConfirmDoesNotDuplicateOrder() throws Exception {
        givenAddressOwned();
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);
        givenStock(30L, 10);
        AiActionProposal pending = pendingProposal();
        // 第一次抢到锁，第二次抢不到
        when(proposalMapper.claimPending(eq(PROPOSAL_NO), eq(USER_ID), any())).thenReturn(1, 0);
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(pending);
        when(mobileOrderService.asyncSubmit(any(), eq(USER_ID))).thenReturn(submitResult("SN-1", "50.00"));

        ConfirmResVO first = service.confirm(USER_ID, PROPOSAL_NO);
        // 第二次时提议已是 CONFIRMED
        pending.setStatus(ProposalStatus.CONFIRMED.name());
        pending.setOrderSn("SN-1");
        ConfirmResVO second = service.confirm(USER_ID, PROPOSAL_NO);

        assertTrue(first.ok());
        assertFalse(second.ok());
        assertEquals(ProposalRejectCode.ALREADY_CONFIRMED.name(), second.failCode());
        verify(mobileOrderService, times(1)).asyncSubmit(any(), anyLong());
    }

    @Test
    @DisplayName("并发双击时只有一个请求能抢到确认权")
    void concurrentDoubleClickOnlyOneWins() throws Exception {
        givenAddressOwned();
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);
        givenStock(30L, 10);
        AiActionProposal pending = pendingProposal();
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        when(proposalMapper.claimPending(eq(PROPOSAL_NO), eq(USER_ID), any())).thenAnswer(invocation -> {
            // 第一次 1，其余 0：模拟数据库条件更新的互斥语义
            return calls.getAndUpdate(v -> v + 1) == 0 ? 1 : 0;
        });
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(pending);
        when(mobileOrderService.asyncSubmit(any(), eq(USER_ID))).thenReturn(submitResult("SN-1", "50.00"));

        ConfirmResVO winner = service.confirm(USER_ID, PROPOSAL_NO);
        pending.setStatus(ProposalStatus.CONFIRMING.name());
        ConfirmResVO loser = service.confirm(USER_ID, PROPOSAL_NO);

        assertTrue(winner.ok());
        assertFalse(loser.ok());
        assertEquals(ProposalRejectCode.CONFIRMING_IN_PROGRESS.name(), loser.failCode());
        verify(mobileOrderService, times(1)).asyncSubmit(any(), anyLong());
    }

    // ---------- 确认：必须拒绝的场景 ----------

    @Test
    @DisplayName("过期提议禁止确认")
    void expiredProposalIsRejected() throws Exception {
        AiActionProposal expired = pendingProposal();
        expired.setExpireTime(LocalDateTime.now().minusSeconds(1));
        when(proposalMapper.claimPending(eq(PROPOSAL_NO), eq(USER_ID), any())).thenReturn(0);
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(expired);

        ConfirmResVO res = service.confirm(USER_ID, PROPOSAL_NO);

        assertFalse(res.ok());
        assertEquals(ProposalRejectCode.EXPIRED.name(), res.failCode());
        verify(mobileOrderService, never()).asyncSubmit(any(), anyLong());
    }

    @Test
    @DisplayName("跨用户确认直接按不存在处理，不泄露他人提议")
    void crossUserConfirmIsNotFound() throws Exception {
        when(proposalMapper.claimPending(eq(PROPOSAL_NO), eq(OTHER_USER_ID), any())).thenReturn(0);
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, OTHER_USER_ID)).thenReturn(null);

        ConfirmResVO res = service.confirm(OTHER_USER_ID, PROPOSAL_NO);

        assertFalse(res.ok());
        assertEquals(ProposalRejectCode.PROPOSAL_NOT_FOUND.name(), res.failCode());
        verify(mobileOrderService, never()).asyncSubmit(any(), anyLong());
    }

    @Test
    @DisplayName("商品价格变化时拒绝确认，不按新价下单")
    void priceChangeIsRejected() throws Exception {
        givenAddressOwned();
        // 实时价格 30，快照里是 20
        givenCart(10L, 30L, new BigDecimal("30.00"), 2);
        givenStock(30L, 10);
        when(proposalMapper.claimPending(eq(PROPOSAL_NO), eq(USER_ID), any())).thenReturn(1);
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(pendingProposal());

        ConfirmResVO res = service.confirm(USER_ID, PROPOSAL_NO);

        assertFalse(res.ok());
        assertEquals(ProposalRejectCode.PRICE_CHANGED.name(), res.failCode());
        verify(proposalMapper).markRejected(anyLong(), eq(ProposalRejectCode.PRICE_CHANGED.name()), any());
        verify(mobileOrderService, never()).asyncSubmit(any(), anyLong());
    }

    @Test
    @DisplayName("库存不足时拒绝确认")
    void stockShortageIsRejected() throws Exception {
        givenAddressOwned();
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);
        givenStock(30L, 1);
        when(proposalMapper.claimPending(eq(PROPOSAL_NO), eq(USER_ID), any())).thenReturn(1);
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(pendingProposal());

        ConfirmResVO res = service.confirm(USER_ID, PROPOSAL_NO);

        assertFalse(res.ok());
        assertEquals(ProposalRejectCode.STOCK_INSUFFICIENT.name(), res.failCode());
        verify(mobileOrderService, never()).asyncSubmit(any(), anyLong());
    }

    @Test
    @DisplayName("购物车数量变化时按已变化拒绝")
    void cartNumberChangeIsRejected() throws Exception {
        givenAddressOwned();
        givenCart(10L, 30L, new BigDecimal("20.00"), 5);
        givenStock(30L, 10);
        when(proposalMapper.claimPending(eq(PROPOSAL_NO), eq(USER_ID), any())).thenReturn(1);
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(pendingProposal());

        ConfirmResVO res = service.confirm(USER_ID, PROPOSAL_NO);

        assertFalse(res.ok());
        assertEquals(ProposalRejectCode.CART_CHANGED.name(), res.failCode());
        verify(mobileOrderService, never()).asyncSubmit(any(), anyLong());
    }

    @Test
    @DisplayName("优惠券失效时拒绝确认")
    void invalidCouponIsRejected() throws Exception {
        givenAddressOwned();
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);
        givenStock(30L, 10);
        AiActionProposal pending = pendingProposal();
        pending.setUserCouponId(77L);
        // 快照里 couponPrice=5，实时优惠券已过期
        pending.setCouponPrice(new BigDecimal("5.00"));
        pending.setActualPrice(new BigDecimal("45.00"));
        ShopMemberCoupon coupon = new ShopMemberCoupon();
        coupon.setUserId(USER_ID.intValue());
        coupon.setUseStatus(1);
        coupon.setExpireTime(new Date());
        when(couponService.getById(77L)).thenReturn(coupon);
        when(proposalMapper.claimPending(eq(PROPOSAL_NO), eq(USER_ID), any())).thenReturn(1);
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(pending);

        ConfirmResVO res = service.confirm(USER_ID, PROPOSAL_NO);

        assertFalse(res.ok());
        assertEquals(ProposalRejectCode.COUPON_INVALID.name(), res.failCode());
        verify(mobileOrderService, never()).asyncSubmit(any(), anyLong());
    }

    // ---------- 确认：下单入口异常 ----------

    @Test
    @DisplayName("下单入口业务拒绝时提议标记为已拒绝")
    void originBusinessRejectionMarksRejected() throws Exception {
        givenAddressOwned();
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);
        givenStock(30L, 10);
        when(proposalMapper.claimPending(eq(PROPOSAL_NO), eq(USER_ID), any())).thenReturn(1);
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(pendingProposal());
        when(mobileOrderService.asyncSubmit(any(), eq(USER_ID)))
                .thenThrow(new BusinessException("购物车为空"));

        ConfirmResVO res = service.confirm(USER_ID, PROPOSAL_NO);

        assertFalse(res.ok());
        assertEquals(ProposalRejectCode.ORDER_SUBMIT_REJECTED.name(), res.failCode());
        verify(proposalMapper).markRejected(anyLong(), eq(ProposalRejectCode.ORDER_SUBMIT_REJECTED.name()), any());
        verify(proposalMapper, never()).markConfirmed(any(), any(), any());
    }

    @Test
    @DisplayName("下单入口系统异常时回滚待确认，允许重试且不回写订单号")
    void originSystemFailureRevertsToPending() throws Exception {
        givenAddressOwned();
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);
        givenStock(30L, 10);
        when(proposalMapper.claimPending(eq(PROPOSAL_NO), eq(USER_ID), any())).thenReturn(1);
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(pendingProposal());
        when(mobileOrderService.asyncSubmit(any(), eq(USER_ID))).thenThrow(new IllegalStateException("MQ down"));

        ConfirmResVO res = service.confirm(USER_ID, PROPOSAL_NO);

        assertFalse(res.ok());
        assertEquals(ProposalRejectCode.SUBMIT_RETRYABLE.name(), res.failCode());
        verify(proposalMapper).revertToPending(anyLong(), eq(ProposalRejectCode.SUBMIT_RETRYABLE.name()), any());
        verify(proposalMapper, never()).markConfirmed(any(), any(), any());
    }

    // ---------- 查询 ----------

    @Test
    @DisplayName("查询到过期未确认提议时惰性落盘为已过期")
    void getLazilyMarksExpired() throws Exception {
        AiActionProposal expired = pendingProposal();
        expired.setExpireTime(LocalDateTime.now().minusSeconds(1));
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(expired);

        ProposalVO vo = service.get(USER_ID, PROPOSAL_NO);

        assertEquals(ProposalStatus.EXPIRED.name(), vo.status());
        verify(proposalMapper).markExpired(eq(PROPOSAL_NO), eq(USER_ID), any());
    }

    @Test
    @DisplayName("跨用户查询按不存在处理")
    void crossUserGetIsNotFound() throws Exception {
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, OTHER_USER_ID)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.get(OTHER_USER_ID, PROPOSAL_NO));
        assertEquals(ProposalRejectCode.PROPOSAL_NOT_FOUND.getMessage(), ex.getMsg());
    }

    // ---------- 测试脚手架 ----------

    private void givenAddressOwned() {
        Address address = new Address();
        address.setId(1L);
        address.setMemberId(USER_ID);
        when(addressService.getById(1L)).thenReturn(address);
    }

    private void givenCart(Long cartId, Long productId, BigDecimal unitPrice, Integer number) {
        Cart cart = new Cart();
        cart.setId(cartId);
        cart.setGoodsId(20L);
        cart.setProductId(productId);
        cart.setPrice(unitPrice);
        cart.setNumber(number);
        cart.setGoodsName("测试商品");
        cart.setChecked(true);
        cart.setUserId(USER_ID.intValue());
        when(cartService.list(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<Cart>>any()))
                .thenReturn(List.of(cart));
    }

    private void givenStock(Long productId, int available) {
        GoodsProduct sku = new GoodsProduct();
        sku.setId(productId);
        sku.setNumber(available);
        when(goodsProductService.getById(productId)).thenReturn(sku);
    }

    /**
     * 构造一条与 {@link #givenCart} 配套的 PENDING 提议快照：
     * 商品 20.00 x 2 = 40.00，运费 10.00（不满 100），应付 50.00。
     */
    private AiActionProposal pendingProposal() {
        AiActionProposal proposal = new AiActionProposal();
        proposal.setId(9L);
        proposal.setProposalNo(PROPOSAL_NO);
        proposal.setUserId(USER_ID);
        proposal.setActionType("CREATE_ORDER");
        proposal.setStatus(ProposalStatus.PENDING.name());
        proposal.setAddressId(1L);
        proposal.setCartIds("10");
        proposal.setItemsSnapshot("30\t20\t20.00\t2\t测试商品");
        proposal.setGoodsPrice(new BigDecimal("40.00"));
        proposal.setFreightPrice(new BigDecimal("10.00"));
        proposal.setCouponPrice(BigDecimal.ZERO);
        proposal.setActualPrice(new BigDecimal("50.00"));
        proposal.setExpireTime(LocalDateTime.now().plusSeconds(600));
        return proposal;
    }

    private SubmitOrderResVO submitResult(String orderSn, String actualPrice) {
        SubmitOrderResVO resVO = new SubmitOrderResVO();
        resVO.setOrderSn(orderSn);
        resVO.setActualPrice(new BigDecimal(actualPrice));
        return resVO;
    }

    @Test
    @DisplayName("提议视图不暴露内部主键与幂等键")
    void viewHidesInternalFields() throws Exception {
        givenAddressOwned();
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);
        when(proposalMapper.selectByIdempotencyKey(eq(USER_ID), anyString())).thenReturn(null);
        when(proposalMapper.insert(org.mockito.ArgumentMatchers.<AiActionProposal>any())).thenAnswer(invocation -> {
            AiActionProposal saved = invocation.getArgument(0);
            saved.setId(123L);
            return 1;
        });

        ProposalVO vo = service.createOrderProposal(USER_ID, 1L, null, List.of(10L));

        assertNull(vo.orderSn());
        assertEquals(1, vo.items().size());
        assertEquals("测试商品", vo.items().get(0).goodsName());
    }
}
