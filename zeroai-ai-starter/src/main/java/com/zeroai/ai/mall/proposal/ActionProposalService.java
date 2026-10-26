package com.zeroai.ai.mall.proposal;

import com.zeroai.ai.mall.proposal.entity.AiActionProposal;
import com.zeroai.ai.mall.proposal.mapper.AiActionProposalMapper;
import com.zeroai.ai.mall.proposal.vo.ConfirmResVO;
import com.zeroai.ai.mall.proposal.vo.ProposalVO;
import com.zeroai.domain.api.cart.entity.Cart;
import com.zeroai.domain.api.cart.service.ICartService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 写操作提议服务：生成待确认提议、执行确认、状态迁移。
 * <p>
 * <b>本类是 Phase 6 Human-in-the-loop 的唯一写入口</b>。边界：
 * <ul>
 *   <li>Agent 侧只能调用 {@link #createOrderProposal}，该方法<b>不会创建订单</b>，
 *       只往 {@code ai_action_proposal} 落一条待确认记录；</li>
 *   <li>订单写入只发生在 {@link #confirm}，且只能由用户通过独立确认接口触发；
 *       内部严格调用原下单入口 {@link IMobileOrderService#asyncSubmit}，
 *       不复制订单创建、库存冻结或优惠计算逻辑。</li>
 *   <li>确认前逐项重校验：价格、数量、库存、优惠券、地址归属、提议过期与状态。
 *       任一不通过就拒绝确认，绝不「按新价先下单再解释」。</li>
 *   <li>确认状态迁移依赖数据库条件更新（见 {@link AiActionProposalMapper}），
 *       并发双击下只有一个请求能真正触发下单。</li>
 * </ul>
 * 幂等：同一用户同一购物车快照只生成一条提议（{@code uk_ai_action_proposal_idem}）；
 * 即便极端情况下重复调用下单入口，原入口还有购物车指纹幂等兜底。
 */
public class ActionProposalService {

    private static final Logger log = LoggerFactory.getLogger(ActionProposalService.class);

    /** 动作类型：创建订单。当前唯一支持的写操作提议。 */
    private static final String ACTION_CREATE_ORDER = "CREATE_ORDER";

    /** 提议编号长度（32 位十六进制短 UUID），足够不可枚举。 */
    private static final int PROPOSAL_NO_LENGTH = 32;

    /** 快照行分隔符，与 {@link ProposalItem#toLine()} 保持一致。 */
    private static final String ITEM_LINE_SEPARATOR = "\n";

    /** 过期时间输出格式，前端可直接展示。 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final AiActionProposalMapper proposalMapper;
    private final ICartService cartService;
    private final IGoodsProductService goodsProductService;
    private final ShopMemberCouponService couponService;
    private final IAddressService addressService;
    private final IMobileOrderService mobileOrderService;
    private final ActionProposalProperties properties;

    /**
     * @param proposalMapper     提议 Mapper
     * @param cartService        购物车服务，只读取
     * @param goodsProductService 商品货品服务，只读取库存
     * @param couponService      用户优惠券服务，只读取
     * @param addressService     收货地址服务，只读取
     * @param mobileOrderService 订单服务，仅调用原下单入口
     * @param properties         提议配置
     */
    public ActionProposalService(AiActionProposalMapper proposalMapper,
                                 ICartService cartService,
                                 IGoodsProductService goodsProductService,
                                 ShopMemberCouponService couponService,
                                 IAddressService addressService,
                                 IMobileOrderService mobileOrderService,
                                 ActionProposalProperties properties) {
        this.proposalMapper = proposalMapper;
        this.cartService = cartService;
        this.goodsProductService = goodsProductService;
        this.couponService = couponService;
        this.addressService = addressService;
        this.mobileOrderService = mobileOrderService;
        this.properties = properties;
    }

    /**
     * 生成「创建订单」提议。<b>不创建订单</b>，只落一条待确认记录。
     *
     * @param userId      当前登录用户 ID，必须来自服务端鉴权
     * @param addressId   收货地址 ID
     * @param userCouponId 用户优惠券 ID，可为空
     * @param cartIds     参与下单的购物车 ID；为空表示取该用户全部已勾选商品
     * @return 提议视图，含对外编号与价格摘要
     */
    public ProposalVO createOrderProposal(Long userId, Long addressId, Long userCouponId, List<Long> cartIds) {
        if (userId == null) {
            throw new IllegalStateException("未登录用户不允许生成下单提议");
        }
        if (addressId == null) {
            throw new IllegalArgumentException("缺少收货地址");
        }
        Address address = addressService.getById(addressId);
        if (address == null || !Objects.equals(address.getMemberId(), userId)) {
            throw new BusinessException(ProposalRejectCode.ADDRESS_INVALID.getMessage());
        }

        List<Cart> carts = resolveCarts(userId, cartIds);
        List<ProposalItem> items = toItems(carts);
        if (items.size() > properties.getMaxItemsPerProposal()) {
            throw new IllegalArgumentException("单次下单商品种类超过上限 " + properties.getMaxItemsPerProposal());
        }

        PriceSummary price = calculatePrice(items, userCouponId, userId);
        String idempotencyKey = buildIdempotencyKey(userId, addressId, userCouponId, items);

        // 命中幂等键直接复用既有提议，避免用户重复问「帮我下单」时生成一堆待确认记录
        AiActionProposal existing = proposalMapper.selectByIdempotencyKey(userId, idempotencyKey);
        if (existing != null) {
            return toView(existing);
        }

        AiActionProposal proposal = new AiActionProposal();
        proposal.setProposalNo(newProposalNo());
        proposal.setUserId(userId);
        proposal.setActionType(ACTION_CREATE_ORDER);
        proposal.setStatus(ProposalStatus.PENDING.name());
        proposal.setIdempotencyKey(idempotencyKey);
        proposal.setAddressId(addressId);
        proposal.setUserCouponId(userCouponId);
        proposal.setCartIds(joinIds(carts));
        proposal.setItemsSnapshot(toSnapshot(items));
        proposal.setGoodsPrice(price.goodsPrice());
        proposal.setFreightPrice(price.freightPrice());
        proposal.setCouponPrice(price.couponPrice());
        proposal.setActualPrice(price.actualPrice());
        proposal.setExpireTime(LocalDateTime.now().plusSeconds(properties.getProposalTtlSeconds()));
        proposal.setCreateTime(LocalDateTime.now());
        proposal.setUpdateTime(LocalDateTime.now());

        try {
            proposalMapper.insert(proposal);
        } catch (DuplicateKeyException e) {
            // 并发下同一快照被两条请求同时插入，唯一键兜住；复用先到的那条即可
            AiActionProposal winner = proposalMapper.selectByIdempotencyKey(userId, idempotencyKey);
            if (winner != null) {
                return toView(winner);
            }
            throw e;
        }
        log.info("已生成下单提议 proposalNo={}, userId={}, actualPrice={}, itemCount={}",
                proposal.getProposalNo(), userId, price.actualPrice(), items.size());
        return toView(proposal);
    }

    /**
     * 确认提议并触发下单。
     * <p>
     * 执行顺序严格为：<b>抢状态锁 → 重校验 → 调用原下单入口 → 回写结果</b>。
     * 任何一步失败都不会出现「先返回成功再补写」；下单入口异常且未落单时回滚到 PENDING 允许重试。
     *
     * @param userId     当前登录用户 ID
     * @param proposalNo 对外提议编号
     * @return 确认结果；{@code ok=false} 时未创建任何订单
     */
    public ConfirmResVO confirm(Long userId, String proposalNo) {
        if (userId == null) {
            throw new IllegalStateException("未登录用户不允许确认下单提议");
        }
        LocalDateTime now = LocalDateTime.now();
        int claimed = proposalMapper.claimPending(proposalNo, userId, now);
        if (claimed == 0) {
            return explainFailedClaim(userId, proposalNo, now);
        }

        AiActionProposal proposal = proposalMapper.selectByProposalNo(proposalNo, userId);
        if (proposal == null) {
            // 抢到锁后立刻查不到，理论上不可能；按未知失败处理，不猜测原因
            return ConfirmResVO.failure(proposalNo, ProposalRejectCode.PROPOSAL_NOT_FOUND);
        }

        try {
            revalidate(proposal, userId);
        } catch (ProposalRejectedException e) {
            proposalMapper.markRejected(proposal.getId(), e.getCode().name(), LocalDateTime.now());
            log.info("下单提议被拒绝 proposalNo={}, userId={}, reason={}", proposalNo, userId, e.getCode());
            return ConfirmResVO.failure(proposalNo, e.getCode());
        }

        try {
            SubmitOrderResVO resVO = submitThroughOrigin(proposal, userId);
            proposalMapper.markConfirmed(proposal.getId(), resVO.getOrderSn(), LocalDateTime.now());
            log.info("下单提议确认成功 proposalNo={}, userId={}, orderSn={}", proposalNo, userId, resVO.getOrderSn());
            // 原入口返回的金额才是权威值：若与快照不一致说明运费/优惠规则有变，如实下发
            return ConfirmResVO.success(proposalNo, resVO.getOrderSn(), resVO.getActualPrice());
        } catch (BusinessException e) {
            // 原下单入口的业务拒绝（购物车为空、优惠券不满足等）：提议已过时，必须重新生成
            log.info("原下单入口拒绝 proposalNo={}, userId={}, msg={}", proposalNo, userId, e.getMsg());
            proposalMapper.markRejected(proposal.getId(), ProposalRejectCode.ORDER_SUBMIT_REJECTED.name(), LocalDateTime.now());
            return ConfirmResVO.failure(proposalNo, ProposalRejectCode.ORDER_SUBMIT_REJECTED);
        } catch (RuntimeException e) {
            /*
             * 系统异常：回滚到 PENDING 允许重试。
             * 安全性依据：原下单入口在消息投递失败时会释放自身幂等占位并抛出，
             * 即「抛异常 = 没有下单」；即便极端情况下消息已投递，
             * 重试会命中购物车指纹幂等并返回同一个订单号，不会产生第二张订单。
             */
            log.error("下单提议确认异常，已回滚待确认状态 proposalNo={}, userId={}", proposalNo, userId, e);
            proposalMapper.revertToPending(proposal.getId(), ProposalRejectCode.SUBMIT_RETRYABLE.name(), LocalDateTime.now());
            return ConfirmResVO.failure(proposalNo, ProposalRejectCode.SUBMIT_RETRYABLE);
        }
    }

    /**
     * 查询提议，跨用户查询直接返回「不存在」，不暴露他人提议是否存在。
     *
     * @param userId     当前登录用户 ID
     * @param proposalNo 对外提议编号
     * @return 提议视图
     */
    public ProposalVO get(Long userId, String proposalNo) {
        if (userId == null) {
            throw new IllegalStateException("未登录用户不允许查看下单提议");
        }
        AiActionProposal proposal = proposalMapper.selectByProposalNo(proposalNo, userId);
        if (proposal == null) {
            throw new BusinessException(ProposalRejectCode.PROPOSAL_NOT_FOUND.getMessage());
        }
        // 惰性过期：读到过期的 PENDING 就落盘，保证状态与判定一致
        if (ProposalStatus.PENDING.name().equals(proposal.getStatus())
                && proposal.getExpireTime() != null
                && !proposal.getExpireTime().isAfter(LocalDateTime.now())) {
            proposalMapper.markExpired(proposalNo, userId, LocalDateTime.now());
            proposal.setStatus(ProposalStatus.EXPIRED.name());
            proposal.setFailCode(ProposalRejectCode.EXPIRED.name());
        }
        return toView(proposal);
    }

    /**
     * 确认失败时解释具体原因。抢锁失败不等于「不存在」，需要区分已确认、处理中、已过期。
     *
     * @param userId     当前用户 ID
     * @param proposalNo 提议编号
     * @param now        当前时间
     * @return 失败结果
     */
    private ConfirmResVO explainFailedClaim(Long userId, String proposalNo, LocalDateTime now) {
        // 过期的 PENDING 先落盘，后续查询结果与失败原因保持一致
        proposalMapper.markExpired(proposalNo, userId, now);
        AiActionProposal proposal = proposalMapper.selectByProposalNo(proposalNo, userId);
        if (proposal == null) {
            return ConfirmResVO.failure(proposalNo, ProposalRejectCode.PROPOSAL_NOT_FOUND);
        }
        // 过期判定看时间而不是看落库后的状态：markExpired 是惰性的，可能因并发尚未写入
        boolean expired = proposal.getExpireTime() != null && !proposal.getExpireTime().isAfter(now);
        ProposalStatus status = safeStatus(proposal.getStatus());
        ProposalRejectCode code;
        if (expired || status == ProposalStatus.EXPIRED) {
            code = ProposalRejectCode.EXPIRED;
        } else if (status == ProposalStatus.CONFIRMED) {
            code = ProposalRejectCode.ALREADY_CONFIRMED;
        } else if (status == ProposalStatus.CONFIRMING) {
            code = ProposalRejectCode.CONFIRMING_IN_PROGRESS;
        } else {
            // PENDING（仍在有效期内但没抢到锁）与 REJECTED 统一按处理中/不存在对外说明
            code = status == ProposalStatus.PENDING
                    ? ProposalRejectCode.CONFIRMING_IN_PROGRESS
                    : ProposalRejectCode.PROPOSAL_NOT_FOUND;
        }
        return ConfirmResVO.failure(proposalNo, code);
    }

    /**
     * 确认前重校验。任何一项不通过都抛 {@link ProposalRejectedException}，调用方负责写 REJECTED。
     *
     * @param proposal 已抢到状态锁的提议
     * @param userId   当前用户 ID
     */
    private void revalidate(AiActionProposal proposal, Long userId) {
        // 1) 归属与过期（抢锁 SQL 已过滤一次，这里再兜一层，防止状态被并发改写）
        if (!Objects.equals(proposal.getUserId(), userId)) {
            throw new ProposalRejectedException(ProposalRejectCode.PROPOSAL_NOT_FOUND);
        }
        if (proposal.getExpireTime() != null && !proposal.getExpireTime().isAfter(LocalDateTime.now())) {
            throw new ProposalRejectedException(ProposalRejectCode.EXPIRED);
        }
        if (ACTION_CREATE_ORDER.equals(proposal.getActionType()) == false) {
            throw new ProposalRejectedException(ProposalRejectCode.PROPOSAL_NOT_FOUND);
        }

        List<ProposalItem> snapshot = parseSnapshot(proposal.getItemsSnapshot());

        // 2) 地址仍属于本人
        Address address = addressService.getById(proposal.getAddressId());
        if (address == null || !Objects.equals(address.getMemberId(), userId)) {
            throw new ProposalRejectedException(ProposalRejectCode.ADDRESS_INVALID);
        }

        // 3) 购物车实时数据与快照逐项比对：价格、数量任一变化都拒绝，绝不按新价下单
        List<Long> cartIds = parseIds(proposal.getCartIds());
        List<Cart> liveCarts = resolveCarts(userId, cartIds);
        List<ProposalItem> liveItems = toItems(liveCarts);
        if (liveItems.size() != snapshot.size()) {
            throw new ProposalRejectedException(ProposalRejectCode.CART_CHANGED);
        }
        for (int i = 0; i < snapshot.size(); i++) {
            ProposalItem before = snapshot.get(i);
            ProposalItem after = liveItems.get(i);
            if (!Objects.equals(before.productId(), after.productId())
                    || !Objects.equals(before.number(), after.number())) {
                throw new ProposalRejectedException(ProposalRejectCode.CART_CHANGED);
            }
            if (before.unitPrice().compareTo(after.unitPrice()) != 0) {
                throw new ProposalRejectedException(ProposalRejectCode.PRICE_CHANGED);
            }
        }

        // 4) 库存充足（只读查询，不冻结）
        for (ProposalItem item : liveItems) {
            GoodsProduct sku = goodsProductService.getById(item.productId());
            int available = sku == null || sku.getNumber() == null ? 0 : sku.getNumber();
            if (available < item.number()) {
                throw new ProposalRejectedException(ProposalRejectCode.STOCK_INSUFFICIENT);
            }
        }

        // 5) 价格与优惠按实时数据重算，与快照比对
        PriceSummary live = calculatePrice(liveItems, proposal.getUserCouponId(), userId);
        if (live.actualPrice().compareTo(proposal.getActualPrice()) != 0
                || live.couponPrice().compareTo(proposal.getCouponPrice()) != 0) {
            throw new ProposalRejectedException(ProposalRejectCode.PRICE_CHANGED);
        }
    }

    /**
     * 调用原下单入口完成下单。<b>不复制</b>订单创建、库存冻结或优惠计算逻辑。
     *
     * @param proposal 提议
     * @param userId   当前用户 ID
     * @return 原入口返回的订单号与权威金额
     */
    private SubmitOrderResVO submitThroughOrigin(AiActionProposal proposal, Long userId) {
        OrderCommitReqVO reqVO = new OrderCommitReqVO();
        reqVO.setAddressId(proposal.getAddressId());
        reqVO.setUserCouponId(proposal.getUserCouponId());
        reqVO.setCartIdArr(parseIds(proposal.getCartIds()));
        try {
            return mobileOrderService.asyncSubmit(reqVO, userId);
        } catch (BusinessException e) {
            // 业务拒绝原样上抛，由调用方写 REJECTED
            throw e;
        } catch (Exception e) {
            // 接口声明的受检异常统一按可重试系统异常处理：原入口在抛出前不会留下已下单状态
            throw new IllegalStateException("下单入口执行失败", e);
        }
    }

    /**
     * 解析本次下单使用的购物车商品，必须全部属于当前用户。
     *
     * @param userId  当前用户 ID
     * @param cartIds 购物车 ID；为空表示取全部已勾选
     * @return 购物车列表
     */
    private List<Cart> resolveCarts(Long userId, List<Long> cartIds) {
        List<Cart> carts;
        if (cartIds == null || cartIds.isEmpty()) {
            carts = cartService.list(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(Cart.class)
                    .eq(Cart::getChecked, true)
                    .eq(Cart::getUserId, userId));
        } else {
            List<Long> distinct = new LinkedHashSet<>(cartIds).stream().toList();
            carts = cartService.list(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(Cart.class)
                    .eq(Cart::getUserId, userId)
                    .in(Cart::getId, distinct));
            if (carts.size() != distinct.size()) {
                // 数量不一致说明存在越权、失效或已删除购物车项，必须中断而不是部分下单
                throw new BusinessException(ProposalRejectCode.CART_CHANGED.getMessage());
            }
        }
        if (carts == null || carts.isEmpty()) {
            throw new BusinessException(ProposalRejectCode.CART_CHANGED.getMessage());
        }
        return carts;
    }

    /**
     * 把购物车实体转成提议快照行。
     *
     * @param carts 购物车列表
     * @return 快照行
     */
    private List<ProposalItem> toItems(List<Cart> carts) {
        List<ProposalItem> items = new ArrayList<>(carts.size());
        for (Cart cart : carts) {
            items.add(new ProposalItem(cart.getProductId(), cart.getGoodsId(), cart.getPrice(), cart.getNumber(), cart.getGoodsName()));
        }
        return items;
    }

    /**
     * 按实时数据计算价格摘要。
     * <p>
     * 运费规则与原下单入口 {@code OrderSubmitSupport} 保持一致（低于免运费门槛收运费）。
     * 两边必须同步修改；确认成功后以原入口返回的金额为准下发，避免规则漂移让用户付错价。
     *
     * @param items        商品行
     * @param userCouponId 用户优惠券 ID，可为空
     * @param userId       当前用户 ID
     * @return 价格摘要
     */
    private PriceSummary calculatePrice(List<ProposalItem> items, Long userCouponId, Long userId) {
        BigDecimal goodsPrice = BigDecimal.ZERO;
        for (ProposalItem item : items) {
            goodsPrice = goodsPrice.add(item.unitPrice().multiply(BigDecimal.valueOf(item.number())));
        }
        BigDecimal freightLimit = ZeroaiConfig.getFreightLimit();
        BigDecimal freightPrice = ZeroaiConfig.getFreightPrice();
        BigDecimal freight = (freightLimit == null || freightPrice == null || goodsPrice.compareTo(freightLimit) >= 0)
                ? BigDecimal.ZERO
                : freightPrice;

        BigDecimal orderTotal = goodsPrice.add(freight).max(BigDecimal.ZERO);
        BigDecimal couponPrice = BigDecimal.ZERO;
        if (userCouponId != null) {
            ShopMemberCoupon coupon = couponService.getById(userCouponId);
            validateCoupon(coupon, userId, orderTotal);
            couponPrice = BigDecimal.valueOf(coupon.getDiscount());
        }
        BigDecimal actualPrice = orderTotal.subtract(couponPrice).max(BigDecimal.ZERO);
        return new PriceSummary(goodsPrice, freight, couponPrice, actualPrice);
    }

    /**
     * 校验优惠券可用性。规则与原下单入口的 {@code OrderValidationSupport.validateCoupon} 一致。
     *
     * @param coupon        用户优惠券
     * @param userId        当前用户 ID
     * @param orderTotalPrice 订单总价（含运费）
     */
    private void validateCoupon(ShopMemberCoupon coupon, Long userId, BigDecimal orderTotalPrice) {
        if (coupon == null || coupon.getUserId() == null || !Objects.equals(coupon.getUserId().longValue(), userId)) {
            throw new ProposalRejectedException(ProposalRejectCode.COUPON_INVALID);
        }
        boolean unused = Objects.equals(coupon.getUseStatus(), 0);
        boolean notExpired = coupon.getExpireTime() == null || coupon.getExpireTime().after(java.util.Date.from(java.time.Instant.now()));
        if (!unused || !notExpired) {
            throw new ProposalRejectedException(ProposalRejectCode.COUPON_INVALID);
        }
        if (coupon.getMin() != null && BigDecimal.valueOf(coupon.getMin()).compareTo(orderTotalPrice) > 0) {
            throw new ProposalRejectedException(ProposalRejectCode.COUPON_INVALID);
        }
    }

    /**
     * 生成幂等键。与原下单入口的购物车指纹思路一致，保证同一快照只产生一条提议。
     *
     * @param userId      当前用户 ID
     * @param addressId   地址 ID
     * @param userCouponId 优惠券 ID
     * @param items       商品行
     * @return 43 位 URL 安全摘要
     */
    private String buildIdempotencyKey(Long userId, Long addressId, Long userCouponId, List<ProposalItem> items) {
        String fingerprint = items.stream()
                .map(item -> item.productId() + ":" + item.number() + ":" + item.unitPrice().toPlainString())
                .sorted()
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        String source = userId + ":" + addressId + ":" + userCouponId + ":" + fingerprint;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 生成不可枚举的对外提议编号。
     *
     * @return 32 位十六进制串
     */
    private static String newProposalNo() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, PROPOSAL_NO_LENGTH);
    }

    /**
     * 购物车 ID 列表转逗号串。
     *
     * @param carts 购物车
     * @return 逗号分隔的 ID
     */
    private static String joinIds(List<Cart> carts) {
        StringBuilder sb = new StringBuilder();
        for (Cart cart : carts) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(cart.getId());
        }
        return sb.toString();
    }

    /**
     * 逗号串还原购物车 ID。
     *
     * @param raw 原始串
     * @return ID 列表
     */
    private static List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                ids.add(Long.valueOf(part.trim()));
            }
        }
        return ids;
    }

    /**
     * 快照行序列化。
     *
     * @param items 商品行
     * @return 多行文本
     */
    private static String toSnapshot(List<ProposalItem> items) {
        StringBuilder sb = new StringBuilder();
        for (ProposalItem item : items) {
            if (!sb.isEmpty()) {
                sb.append(ITEM_LINE_SEPARATOR);
            }
            sb.append(item.toLine());
        }
        return sb.toString();
    }

    /**
     * 快照行反序列化。格式非法按内部错误处理，不静默跳过。
     *
     * @param snapshot 快照文本
     * @return 商品行
     */
    private static List<ProposalItem> parseSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            throw new ProposalRejectedException(ProposalRejectCode.CART_CHANGED);
        }
        List<ProposalItem> items = new ArrayList<>();
        for (String line : snapshot.split(ITEM_LINE_SEPARATOR)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                items.add(ProposalItem.fromLine(line));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("提议快照格式非法，无法安全确认", e);
            }
        }
        return items;
    }

    /**
     * 实体转对外视图。刻意不暴露主键、幂等键、地址与联系方式。
     *
     * @param proposal 提议实体
     * @return 对外视图
     */
    private ProposalVO toView(AiActionProposal proposal) {
        List<ProposalVO.ItemVO> items = new ArrayList<>();
        for (ProposalItem item : parseSnapshot(proposal.getItemsSnapshot())) {
            items.add(new ProposalVO.ItemVO(item.goodsName(), item.unitPrice(), item.number()));
        }
        String message = switch (safeStatus(proposal.getStatus())) {
            case PENDING -> "下单提议已生成，需要你确认后才会创建订单";
            case CONFIRMING -> "确认正在处理中，请稍候查看结果";
            case CONFIRMED -> "已为你提交订单";
            case REJECTED -> "下单提议已失效：" + rejectMessage(proposal.getFailCode());
            case EXPIRED -> ProposalRejectCode.EXPIRED.getMessage();
        };
        return new ProposalVO(
                proposal.getProposalNo(),
                proposal.getStatus(),
                proposal.getActionType(),
                proposal.getActualPrice(),
                proposal.getGoodsPrice(),
                proposal.getFreightPrice(),
                proposal.getCouponPrice(),
                proposal.getExpireTime() == null ? null : proposal.getExpireTime().format(TIME_FORMATTER),
                items,
                proposal.getOrderSn(),
                proposal.getFailCode(),
                message);
    }

    /**
     * 状态字符串安全转枚举，未知值按已拒绝处理，避免 NPE 让整个确认链路失败。
     *
     * @param status 状态字符串
     * @return 状态枚举
     */
    private static ProposalStatus safeStatus(String status) {
        if (status == null) {
            return ProposalStatus.REJECTED;
        }
        try {
            return ProposalStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return ProposalStatus.REJECTED;
        }
    }

    /**
     * 失败原因码转文案。
     *
     * @param failCode 原因码字符串
     * @return 中文文案
     */
    private static String rejectMessage(String failCode) {
        if (failCode == null) {
            return "请重新发起下单";
        }
        try {
            return ProposalRejectCode.valueOf(failCode).getMessage();
        } catch (IllegalArgumentException e) {
            return "请重新发起下单";
        }
    }

    /**
     * 价格摘要。
     *
     * @param goodsPrice   商品总价
     * @param freightPrice 运费
     * @param couponPrice  优惠金额
     * @param actualPrice  应付金额
     */
    private record PriceSummary(BigDecimal goodsPrice, BigDecimal freightPrice, BigDecimal couponPrice,
                                BigDecimal actualPrice) {
    }

    /**
     * 内部异常：表示「业务重校验失败，应拒绝确认」，不暴露给调用方。
     */
    private static final class ProposalRejectedException extends RuntimeException {

        private final transient ProposalRejectCode code;

        private ProposalRejectedException(ProposalRejectCode code) {
            super(code.getMessage());
            this.code = code;
        }

        private ProposalRejectCode getCode() {
            return code;
        }
    }
}
