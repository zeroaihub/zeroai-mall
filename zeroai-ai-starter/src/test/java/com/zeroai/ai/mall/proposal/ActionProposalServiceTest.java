package com.zeroai.ai.mall.proposal;

import com.zeroai.ai.mall.event.CartSelection;
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
        // 测试里不真等：确认接口的「等待落单结果」窗口置 0，只做一次结果检查就返回
        properties.setResultWaitMs(0L);
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
    @DisplayName("提议视图带收货地址摘要，用户点确认前能看到寄到哪")
    void proposalViewCarriesAddressSummary() {
        Address address = new Address();
        address.setId(1L);
        address.setMemberId(USER_ID);
        address.setName("张三");
        address.setTel("13800138000");
        address.setProvince("浙江省");
        address.setCity("杭州市");
        address.setCounty("西湖区");
        address.setAddressDetail("文三路 1 号");
        when(addressService.getById(1L)).thenReturn(address);
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);
        when(proposalMapper.selectByIdempotencyKey(eq(USER_ID), anyString())).thenReturn(null);
        when(proposalMapper.insert(org.mockito.ArgumentMatchers.<AiActionProposal>any())).thenReturn(1);

        ProposalVO vo = service.createOrderProposal(USER_ID, 1L, null, List.of(10L));

        // 只有金额和件数的提议等于让用户盲签：地址必须随视图下发
        assertNotNull(vo.addressText(), "提议必须带收货地址摘要");
        assertTrue(vo.addressText().contains("张三"), vo.addressText());
        assertTrue(vo.addressText().contains("文三路 1 号"), vo.addressText());
        assertFalse(vo.addressText().contains("13800138000"), "不得下发完整手机号：" + vo.addressText());
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

    // ---------- 创建提议：勾选状态是唯一的同意信号 ----------

    @Test
    @DisplayName("显式指定未勾选的商品时拒绝生成提议，并带上商品名")
    void createRejectsUncheckedCartItem() throws Exception {
        givenAddressOwned();
        givenCartRow(10L, 30L, new BigDecimal("20.00"), 2, false);

        ProposalValidationException ex = assertThrows(ProposalValidationException.class,
                () -> service.createOrderProposal(USER_ID, 1L, null, List.of(10L)));

        assertEquals(ProposalRejectCode.CART_NOT_CHECKED, ex.getCode());
        assertTrue(ex.getDetail().contains("测试商品"), "明细要带上商品名，模型才能让用户去勾选");
        verify(proposalMapper, never()).insert(org.mockito.ArgumentMatchers.<AiActionProposal>any());
    }

    @Test
    @DisplayName("不传 cartIds 时只取已勾选商品，未勾选项不会混进提议")
    void createWithoutCartIdsUsesCheckedOnly() throws Exception {
        givenAddressOwned();
        givenCartRow(10L, 30L, new BigDecimal("20.00"), 2, true);
        when(proposalMapper.selectByIdempotencyKey(eq(USER_ID), anyString())).thenReturn(null);
        when(proposalMapper.insert(org.mockito.ArgumentMatchers.<AiActionProposal>any())).thenReturn(1);

        ProposalVO vo = service.createOrderProposal(USER_ID, 1L, null, null);

        assertEquals(1, vo.items().size());
        // 关键：查询条件里必须带 checked=true，否则等于把整张购物车都算进提议
        org.mockito.ArgumentMatcher<com.baomidou.mybatisplus.core.conditions.Wrapper<Cart>> checkedOnly =
                wrapper -> wrapper != null && wrapper.getSqlSegment().contains("checked");
        verify(cartService).list(org.mockito.ArgumentMatchers.argThat(checkedOnly));
    }

    @Test
    @DisplayName("cartIds 超过长度上限时直接拒绝，不查库")
    void createRejectsOversizedCartIdList() throws Exception {
        givenAddressOwned();
        List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, 60).boxed().toList();

        ProposalValidationException ex = assertThrows(ProposalValidationException.class,
                () -> service.createOrderProposal(USER_ID, 1L, null, tooMany));

        assertEquals(ProposalRejectCode.CART_CHANGED, ex.getCode());
        verify(cartService, never()).list(org.mockito.ArgumentMatchers
                .<com.baomidou.mybatisplus.core.conditions.Wrapper<Cart>>any());
    }

    @Test
    @DisplayName("cartIds 里的商品已不在购物车时按内容变化拒绝")
    void createRejectsMissingCartItem() throws Exception {
        givenAddressOwned();
        when(cartService.list(org.mockito.ArgumentMatchers
                .<com.baomidou.mybatisplus.core.conditions.Wrapper<Cart>>any())).thenReturn(List.of());

        ProposalValidationException ex = assertThrows(ProposalValidationException.class,
                () -> service.createOrderProposal(USER_ID, 1L, null, List.of(10L)));

        assertEquals(ProposalRejectCode.CART_CHANGED, ex.getCode());
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
        // 消费端已回写落单成功
        when(mobileOrderService.searchSubmitResult("SN-1")).thenReturn("success");

        ConfirmResVO res = service.confirm(USER_ID, PROPOSAL_NO);

        assertTrue(res.ok());
        assertFalse(res.processing(), "拿到落单结果后不应再让前端等待");
        assertEquals("SN-1", res.orderSn());
        ArgumentCaptor<OrderCommitReqVO> captor = ArgumentCaptor.forClass(OrderCommitReqVO.class);
        verify(mobileOrderService, times(1)).asyncSubmit(captor.capture(), eq(USER_ID));
        assertEquals(pending.getAddressId(), captor.getValue().getAddressId());
        // 订单号要在等结果之前先落库，等待超时后仍能回查
        verify(proposalMapper).bindOrderSn(eq(pending.getId()), eq("SN-1"), any());
        verify(proposalMapper).markConfirmed(eq(pending.getId()), eq("SN-1"), any());
    }

    @Test
    @DisplayName("消费端还没回结果时返回处理中，不谎报订单已创建")
    void confirmReportsProcessingWhenNoResultYet() throws Exception {
        givenAddressOwned();
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);
        givenStock(30L, 10);
        AiActionProposal pending = pendingProposal();
        when(proposalMapper.claimPending(eq(PROPOSAL_NO), eq(USER_ID), any())).thenReturn(1);
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(pending);
        when(mobileOrderService.asyncSubmit(any(), eq(USER_ID))).thenReturn(submitResult("SN-1", "50.00"));
        when(mobileOrderService.searchSubmitResult("SN-1")).thenReturn(null);

        ConfirmResVO res = service.confirm(USER_ID, PROPOSAL_NO);

        assertTrue(res.ok(), "消息已入队，不算失败");
        assertTrue(res.processing(), "必须显式告诉前端还没落库");
        assertEquals("SN-1", res.orderSn());
        // 关键：不能提前写 CONFIRMED，否则用户会拿到一个查不到的订单号
        verify(proposalMapper, never()).markConfirmed(any(), any(), any());
    }

    @Test
    @DisplayName("消费端落单失败时按具体原因拒绝，而不是报成功")
    void confirmRejectsWhenConsumerFails() throws Exception {
        givenAddressOwned();
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);
        givenStock(30L, 10);
        AiActionProposal pending = pendingProposal();
        when(proposalMapper.claimPending(eq(PROPOSAL_NO), eq(USER_ID), any())).thenReturn(1);
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(pending);
        when(mobileOrderService.asyncSubmit(any(), eq(USER_ID))).thenReturn(submitResult("SN-1", "50.00"));
        when(mobileOrderService.searchSubmitResult("SN-1")).thenReturn("商品库存不足");

        ConfirmResVO res = service.confirm(USER_ID, PROPOSAL_NO);

        assertFalse(res.ok());
        assertEquals(ProposalRejectCode.ORDER_SUBMIT_REJECTED.name(), res.failCode());
        assertEquals("商品库存不足", res.message(), "消费端给的具体原因要原样透出");
        verify(proposalMapper).markRejected(anyLong(), eq(ProposalRejectCode.ORDER_SUBMIT_REJECTED.name()), any());
        verify(proposalMapper, never()).markConfirmed(any(), any(), any());
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
        when(mobileOrderService.searchSubmitResult("SN-1")).thenReturn("success");

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
        when(mobileOrderService.searchSubmitResult("SN-1")).thenReturn("success");

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
    @DisplayName("提议生成后商品被取消勾选时拒绝确认，且不回滚成可重试")
    void uncheckedAfterProposalIsRejected() throws Exception {
        givenAddressOwned();
        givenCartRow(10L, 30L, new BigDecimal("20.00"), 2, false);
        givenStock(30L, 10);
        when(proposalMapper.claimPending(eq(PROPOSAL_NO), eq(USER_ID), any())).thenReturn(1);
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(pendingProposal());

        ConfirmResVO res = service.confirm(USER_ID, PROPOSAL_NO);

        assertFalse(res.ok());
        assertEquals(ProposalRejectCode.CART_NOT_CHECKED.name(), res.failCode());
        verify(proposalMapper).markRejected(anyLong(), eq(ProposalRejectCode.CART_NOT_CHECKED.name()), any());
        // 关键：不能落到 SUBMIT_RETRYABLE 分支，那会让用户以为「稍后重试」就能成功
        verify(proposalMapper, never()).revertToPending(anyLong(), anyString(), any());
        verify(mobileOrderService, never()).asyncSubmit(any(), anyLong());
    }

    @Test
    @DisplayName("提议生成后购物车商品被删除时按内容变化拒绝，而不是可重试")
    void deletedCartItemIsRejectedNotRetryable() throws Exception {
        givenAddressOwned();
        when(cartService.list(org.mockito.ArgumentMatchers
                .<com.baomidou.mybatisplus.core.conditions.Wrapper<Cart>>any())).thenReturn(List.of());
        givenStock(30L, 10);
        when(proposalMapper.claimPending(eq(PROPOSAL_NO), eq(USER_ID), any())).thenReturn(1);
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(pendingProposal());

        ConfirmResVO res = service.confirm(USER_ID, PROPOSAL_NO);

        assertFalse(res.ok());
        assertEquals(ProposalRejectCode.CART_CHANGED.name(), res.failCode());
        verify(proposalMapper).markRejected(anyLong(), eq(ProposalRejectCode.CART_CHANGED.name()), any());
        verify(proposalMapper, never()).revertToPending(anyLong(), anyString(), any());
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

    @Test
    @DisplayName("查询处理中提议时按落单结果补齐为已确认")
    void getReconcilesConfirmingProposalToConfirmed() throws Exception {
        AiActionProposal confirming = pendingProposal();
        confirming.setStatus(ProposalStatus.CONFIRMING.name());
        confirming.setOrderSn("SN-1");
        confirming.setUpdateTime(LocalDateTime.now().minusSeconds(5));
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(confirming);
        when(mobileOrderService.searchSubmitResult("SN-1")).thenReturn("success");

        ProposalVO vo = service.get(USER_ID, PROPOSAL_NO);

        assertEquals(ProposalStatus.CONFIRMED.name(), vo.status());
        assertEquals("SN-1", vo.orderSn());
        verify(proposalMapper).markConfirmed(eq(confirming.getId()), eq("SN-1"), any());
    }

    @Test
    @DisplayName("处理中提议超过超时时间仍无结果时落盘为超时失败")
    void getTimesOutStuckConfirmingProposal() throws Exception {
        AiActionProposal confirming = pendingProposal();
        confirming.setStatus(ProposalStatus.CONFIRMING.name());
        confirming.setOrderSn("SN-1");
        // 超过默认 120 秒的确认超时窗口
        confirming.setUpdateTime(LocalDateTime.now().minusSeconds(200));
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(confirming);
        when(mobileOrderService.searchSubmitResult("SN-1")).thenReturn(null);

        ProposalVO vo = service.get(USER_ID, PROPOSAL_NO);

        assertEquals(ProposalStatus.REJECTED.name(), vo.status());
        assertEquals(ProposalRejectCode.CONFIRM_TIMEOUT.name(), vo.failCode());
        verify(proposalMapper).markRejected(anyLong(), eq(ProposalRejectCode.CONFIRM_TIMEOUT.name()), any());
        verify(proposalMapper, never()).markConfirmed(any(), any(), any());
    }

    @Test
    @DisplayName("处理中提议在超时窗口内保持处理中，不误判失败")
    void getKeepsConfirmingWithinTimeoutWindow() throws Exception {
        AiActionProposal confirming = pendingProposal();
        confirming.setStatus(ProposalStatus.CONFIRMING.name());
        confirming.setOrderSn("SN-1");
        confirming.setUpdateTime(LocalDateTime.now().minusSeconds(3));
        when(proposalMapper.selectByProposalNo(PROPOSAL_NO, USER_ID)).thenReturn(confirming);
        when(mobileOrderService.searchSubmitResult("SN-1")).thenReturn(null);

        ProposalVO vo = service.get(USER_ID, PROPOSAL_NO);

        assertEquals(ProposalStatus.CONFIRMING.name(), vo.status());
        verify(proposalMapper, never()).markRejected(anyLong(), anyString(), any());
        verify(proposalMapper, never()).markConfirmed(any(), any(), any());
    }

    // ---------- 测试脚手架 ----------

    // ---------- 弹层勾选下单 ----------

    @Test
    @DisplayName("准备下单时取默认地址，并把整份购物车（含未勾选）下发给弹层")
    void prepareSelectionResolvesDefaultAddressAndMapsCart() {
        Address address = new Address();
        address.setId(7L);
        address.setMemberId(USER_ID);
        address.setName("张三");
        address.setTel("13800138000");
        address.setProvince("浙江省");
        address.setCity("杭州市");
        address.setCounty("西湖区");
        address.setAddressDetail("文三路 1 号");
        when(addressService.list(
                org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<Address>>any()))
                .thenReturn(List.of(address));
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);

        CartSelection selection = service.prepareCartSelection(USER_ID, null, null, null);

        assertNotNull(selection);
        assertEquals(7L, selection.addressId(), "未指定地址时必须解析出默认地址");
        assertTrue(selection.addressText().contains("张三"), selection.addressText());
        assertTrue(selection.addressText().contains("138****8000"), "手机号必须脱敏：" + selection.addressText());
        assertFalse(selection.addressText().contains("13800138000"), "不得下发完整手机号");
        assertEquals(1, selection.items().size());
        assertEquals(2, selection.totalNumber());
    }

    @Test
    @DisplayName("没有收货地址时仍弹层，由用户在弹层里选或新增，提交前必须选")
    void prepareSelectionToleratesMissingAddress() {
        when(addressService.list(
                org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<Address>>any()))
                .thenReturn(List.of());
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);

        CartSelection selection = service.prepareCartSelection(USER_ID, null, null, null);

        // 早期实现直接抛错，用户体验是「说要下单 → 被告知没地址」，然后无路可走；
        // 现在弹层照常出现，地址留空由弹层引导补齐
        assertNotNull(selection);
        assertNull(selection.addressId(), "没有地址时不得凭空给一个");
        assertEquals("", selection.addressText());
        assertEquals(1, selection.items().size());
    }

    @Test
    @DisplayName("按勾选生成提议时必须带地址，不能悄悄退回默认地址")
    void createProposalForSelectionRequiresExplicitAddress() {
        givenAddressOwned();
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createProposalForSelection(USER_ID, null, null, List.of(10L)));

        assertTrue(error.getMsg().contains("收货地址"), error.getMsg());
        // 没有生成任何提议：地址未确认就不该出现可确认的卡片
        verify(proposalMapper, never()).insert(any(AiActionProposal.class));
    }

    @Test
    @DisplayName("购物车为空时准备下单返回 null，由工具层引导用户先加购")
    void prepareSelectionReturnsNullOnEmptyCart() {
        Address address = new Address();
        address.setId(7L);
        address.setMemberId(USER_ID);
        when(addressService.list(
                org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<Address>>any()))
                .thenReturn(List.of(address));
        when(cartService.list(
                org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<Cart>>any()))
                .thenReturn(List.of());

        assertNull(service.prepareCartSelection(USER_ID, null, null, null));
    }

    @Test
    @DisplayName("传了 cartIds 时弹层只含这些商品，并按传入顺序排列")
    void prepareSelectionScopesToRequestedCartIds() {
        Address address = new Address();
        address.setId(7L);
        address.setMemberId(USER_ID);
        address.setName("张三");
        address.setTel("13800138000");
        when(addressService.list(
                org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<Address>>any()))
                .thenReturn(List.of(address));
        // 购物车里有两行，用户只说要下第二行：弹层不该把两行都倒出来
        Cart phone = new Cart();
        phone.setId(11L);
        phone.setGoodsId(20L);
        phone.setProductId(30L);
        phone.setPrice(new BigDecimal("2999.00"));
        phone.setNumber(1);
        phone.setGoodsName("小米手机");
        phone.setChecked(false);
        phone.setUserId(USER_ID.intValue());
        Cart other = new Cart();
        other.setId(10L);
        other.setGoodsId(21L);
        other.setProductId(31L);
        other.setPrice(new BigDecimal("19.90"));
        other.setNumber(3);
        other.setGoodsName("数据线");
        other.setChecked(true);
        other.setUserId(USER_ID.intValue());
        when(cartService.list(
                org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<Cart>>any()))
                .thenReturn(List.of(phone, other));

        CartSelection selection = service.prepareCartSelection(USER_ID, null, null, List.of(11L));

        assertEquals(1, selection.items().size(), "只应展示用户要下单的那一件");
        assertEquals(11L, selection.items().get(0).cartId());
        assertEquals("小米手机", selection.items().get(0).goodsName());
        assertEquals(1, selection.totalNumber());
    }

    // ---------- 提议唯一 ----------

    @Test
    @DisplayName("生成新提议前先作废该用户其余待确认提议，保证同时只有一条可确认")
    void createSupersedesPreviousPendingProposals() throws Exception {
        givenAddressOwned();
        givenCart(10L, 30L, new BigDecimal("20.00"), 2);
        when(proposalMapper.selectByIdempotencyKey(eq(USER_ID), anyString())).thenReturn(null);
        when(proposalMapper.insert(org.mockito.ArgumentMatchers.<AiActionProposal>any())).thenReturn(1);

        service.createOrderProposal(USER_ID, 1L, null, List.of(10L));

        verify(proposalMapper).supersedePending(eq(USER_ID), any());
    }

    private void givenAddressOwned() {
        Address address = new Address();
        address.setId(1L);
        address.setMemberId(USER_ID);
        when(addressService.getById(1L)).thenReturn(address);
    }

    private void givenCart(Long cartId, Long productId, BigDecimal unitPrice, Integer number) {
        givenCartRow(cartId, productId, unitPrice, number, true);
    }

    /**
     * 构造一行购物车数据。
     * <p>
     * {@code checked} 单独开一个参数，因为「未勾选商品能不能进提议」正是要断言的安全边界，
     * 复用默认勾选的脚手架会把这条用例写成永远通过。
     */
    private void givenCartRow(Long cartId, Long productId, BigDecimal unitPrice, Integer number,
                              boolean checked) {
        Cart cart = new Cart();
        cart.setId(cartId);
        cart.setGoodsId(20L);
        cart.setProductId(productId);
        cart.setPrice(unitPrice);
        cart.setNumber(number);
        cart.setGoodsName("测试商品");
        cart.setChecked(checked);
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
