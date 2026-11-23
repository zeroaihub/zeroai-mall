package com.zeroai.ai.mall.proposal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zeroai.ai.mall.event.CartSelection;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

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
 *   <li><b>只认购物车勾选状态</b>：未勾选的商品无论如何都不能进入提议或订单，
 *       哪怕模型在参数里显式点名了它——勾选框是用户唯一的显式同意信号。</li>
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

    /** 异步下单成功时消费端写入结果缓存的值，与 {@code SubmitOrderController} 保持一致。 */
    private static final String SUBMIT_RESULT_SUCCESS = "success";

    /**
     * 消费端拒绝时写入的兜底文本。
     * <p>
     * {@code SubmitOrderController} 在拿不到业务异常文案时会写 "error"，
     * 这种值对用户没有信息量，展示前必须换成原因码自带的文案。
     */
    private static final String SUBMIT_RESULT_UNKNOWN = "error";

    /** 提议编号长度（32 位十六进制短 UUID），足够不可枚举。 */
    private static final int PROPOSAL_NO_LENGTH = 32;

    /**
     * 同一购物车快照最多尝试生成几条提议。
     * <p>
     * 每生成一条并走完生命周期（确认/拒绝/过期），同快照的下一条就要占用新的幂等键；
     * 上限只是防死循环，正常用户远达不到。
     */
    private static final int MAX_PROPOSAL_ATTEMPTS = 10;

    /** 快照行分隔符，与 {@link ProposalItem#toLine()} 保持一致。 */
    private static final String ITEM_LINE_SEPARATOR = "\n";

    /**
     * 显式指定购物车行数的硬上限。
     * <p>
     * 模型可能把一长串 ID（甚至重复几千个）塞进来，先做长度封顶再做 SQL：
     * 否则 IN 条件会被撑爆，而「商品种类超限」这条业务校验要等查完库才生效。
     */
    private static final int MAX_CART_IDS = 50;

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
     * @param cartIds     参与下单的购物车 ID；为空表示取该用户全部已勾选商品，
     *                    非空时每一项都必须属于本人且已勾选
     * @return 提议视图，含对外编号与价格摘要
     * @throws ProposalValidationException 商品未勾选、不存在、行数据异常或 ID 列表过长
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

        log.info("生成提议开始 userId={} addressId={} couponId={} cartIds={}",
                userId, addressId, userCouponId, cartIds);
        List<Cart> carts = resolveCarts(userId, cartIds);
        List<ProposalItem> items = toItems(carts);
        log.info("生成提议已解析购物车 userId={} cartRows={} itemKinds={} goodsPricePreview={}",
                userId, carts.size(), items.size(), items.stream()
                        .map(item -> item.unitPrice().multiply(java.math.BigDecimal.valueOf(item.number())))
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        if (items.size() > properties.getMaxItemsPerProposal()) {
            throw new IllegalArgumentException("单次下单商品种类超过上限 " + properties.getMaxItemsPerProposal());
        }

        PriceSummary price = calculatePrice(items, userCouponId, userId);

        /*
         * 幂等语义是「同一快照只存在一个待确认提议」，不是「同一快照永远只有一条提议」。
         * 差别很关键：用户确认过一条提议后再想买同样的东西，如果直接复用旧记录，
         * 拿到的是一条 status=CONFIRMED 的提议，前端卡片看起来还能点，一点就报「已确认过」。
         * 因此这里按「尝试序号」逐个探测幂等键：命中 PENDING 就复用，命中历史状态就换下一个键，
         * 既保留了防重复提交，又让同一购物车快照可以再次生成新提议。
         */
        for (int attempt = 0; attempt < MAX_PROPOSAL_ATTEMPTS; attempt++) {
            String idempotencyKey = buildIdempotencyKey(userId, addressId, userCouponId, items, attempt);
            AiActionProposal existing = proposalMapper.selectByIdempotencyKey(userId, idempotencyKey);
            if (existing != null) {
                if (isReusable(existing)) {
                    // 同一快照重复提交：复用而不是新建，日志要能看出「这次没有写库」
                    log.info("复用既有提议 userId={} proposalNo={} attempt={}",
                            userId, existing.getProposalNo(), attempt);
                    return toView(existing);
                }
                log.info("幂等键命中历史提议，换下一个键 userId={} proposalNo={} status={}",
                        userId, existing.getProposalNo(), existing.getStatus());
                continue;
            }

            AiActionProposal proposal = newProposal(userId, addressId, userCouponId, carts, items,
                    price, idempotencyKey);
            try {
                /*
                 * 插入前先作废该用户其余待确认提议：同一时刻只允许一份可确认的提议。
                 * 顺序不能反——先插入再作废，中间窗口里会短暂出现两条 PENDING，
                 * 并发确认时可能都被人点到。
                 */
                int superseded = proposalMapper.supersedePending(userId, LocalDateTime.now());
                if (superseded > 0) {
                    log.info("已作废旧提议 userId={} superseded={}", userId, superseded);
                }
                proposalMapper.insert(proposal);
            } catch (DuplicateKeyException e) {
                // 并发下同一快照被两条请求同时插入，唯一键兜住；对方仍是待确认才复用，否则换下一个键
                AiActionProposal winner = proposalMapper.selectByIdempotencyKey(userId, idempotencyKey);
                if (winner != null && isReusable(winner)) {
                    return toView(winner);
                }
                continue;
            }
            log.info("已生成下单提议 proposalNo={}, userId={}, actualPrice={}, itemCount={}",
                    proposal.getProposalNo(), userId, price.actualPrice(), items.size());
            return toView(proposal);
        }
        throw new BusinessException("下单提议生成过于频繁，请稍后再试");
    }

    /**
     * 准备下单：读取购物车与收货地址，供前端弹层让用户勾选商品。
     * <p>
     * 只读，不创建任何提议。选择权仍然在用户手里：这里只是把「去哪勾选」从购物车页搬到对话内，
     * 勾选结果由前端通过 {@link #createProposalForSelection} 提交，服务端仍然只认
     * {@code checked=true} 的行。
     *
     * @param userId       当前登录用户 ID
     * @param addressId    收货地址 ID，可为空（为空时取默认地址；没有地址则留空，由用户在弹层里补）
     * @param userCouponId 用户优惠券 ID，可为空
     * @return 购物车与地址快照；购物车为空时返回 null
     * @throws BusinessException 显式传入的地址不属于本人
     */
    public CartSelection prepareCartSelection(Long userId, Long addressId, Long userCouponId,
                                              List<Long> cartIds) {
        if (userId == null) {
            throw new IllegalStateException("未登录用户不允许准备下单");
        }
        /*
         * 地址允许为空：用户还没有收货地址时也要能把弹层弹出来，
         * 否则「说下单 → 被告知没地址」之后就无路可走，只能自己去菜单里找地址页。
         * 弹层里会显示「请选择收货地址」并提供新增入口，提交前仍必须选一个。
         */
        Address address = resolveAddressOrNull(userId, addressId);
        log.info("准备下单选择 userId={} 解析地址 addressId={} requested={} cartIds={}",
                userId, address == null ? null : address.getId(), addressId, cartIds);
        List<Cart> carts = loadSelectionCarts(userId, normalizeCartIds(cartIds));
        if (carts == null || carts.isEmpty()) {
            return null;
        }
        List<CartSelection.Item> items = new ArrayList<>(carts.size());
        int totalNumber = 0;
        for (Cart cart : carts) {
            // 弹层最多展示 MAX_CART_IDS 行，与提交时的上限保持一致，避免「勾得上但提交被拒」
            if (items.size() >= MAX_CART_IDS) {
                break;
            }
            int number = cart.getNumber() == null ? 0 : cart.getNumber();
            totalNumber += number;
            items.add(new CartSelection.Item(cart.getId(), cart.getGoodsId(), cart.getProductId(),
                    cart.getGoodsName(), cart.getPrice(), cart.getNumber(),
                    Boolean.TRUE.equals(cart.getChecked()), cart.getPicUrl()));
        }
        log.info("准备下单选择完成 userId={} addressId={} hasAddress={} itemCount={} totalNumber={}",
                userId, address == null ? null : address.getId(), address != null,
                items.size(), totalNumber);
        return new CartSelection(address == null ? null : address.getId(),
                address == null ? "" : addressText(address), userCouponId, items, totalNumber);
    }

    /**
     * 读取本次要展示给用户的购物车行。
     * <p>
     * 有 {@code cartIds} 时只取这些行——用户说「把小米手机下单」，弹层就不该把整份购物车倒出来；
     * 无 {@code cartIds} 时退回全量（用户说的是「购物车都下单」或模型没能判断）。
     * <p>
     * 缺失的 ID 直接跳过而不是报错：模型手里的 ID 可能来自几轮前，购物车里删掉一行不该让整轮回答失败；
     * 全缺失时返回空列表，由上层给出「购物车为空」的提示。
     *
     * @param userId   当前登录用户 ID
     * @param requested 已规范化的购物车 ID，可为空
     * @return 购物车行，按 {@code requested} 顺序；无匹配时返回空列表
     */
    private List<Cart> loadSelectionCarts(Long userId, List<Long> requested) {
        try {
            if (requested.isEmpty()) {
                return cartService.list(Wrappers.lambdaQuery(Cart.class)
                        .eq(Cart::getUserId, userId)
                        .orderByDesc(Cart::getId));
            }
            List<Cart> found = cartService.list(Wrappers.lambdaQuery(Cart.class)
                    .eq(Cart::getUserId, userId)
                    .in(Cart::getId, requested));
            if (found == null || found.isEmpty()) {
                return List.of();
            }
            // 按模型给的顺序回显：它通常按用户提到的先后排过
            Map<Long, Cart> byId = found.stream()
                    .collect(Collectors.toMap(Cart::getId, cart -> cart, (first, second) -> first));
            return requested.stream().map(byId::get).filter(Objects::nonNull).toList();
        } catch (RuntimeException e) {
            throw new BusinessException("购物车读取失败，请稍后再试");
        }
    }

    /**
     * 按用户勾选结果生成提议。地址允许为空，由服务端解析默认地址。
     * <p>
     * 与 {@link #createOrderProposal} 的唯一差别是地址解析：弹层里用户只勾商品，
     * 地址取默认即可；其余校验、幂等与作废旧提议的语义完全复用，避免两套下单前置逻辑漂移。
     *
     * @param userId       当前登录用户 ID
     * @param addressId    收货地址 ID，可为空
     * @param userCouponId 用户优惠券 ID，可为空
     * @param cartIds      用户勾选的购物车 ID
     * @return 提议视图
     */
    public ProposalVO createProposalForSelection(Long userId, Long addressId,
                                                 Long userCouponId, List<Long> cartIds) {
        // 地址必须由用户在弹层里显式选过：这里拒绝空值，而不是悄悄退回默认地址，
        // 否则「用户没确认地址」又会以另一种形式回来（界面看不出，地址却已定）
        if (addressId == null) {
            throw new BusinessException("请先选择收货地址");
        }
        Long resolved = resolveAddress(userId, addressId).getId();
        log.info("按勾选生成提议 userId={} requestedAddress={} resolvedAddress={} cartIds={}",
                userId, addressId, resolved, cartIds);
        return createOrderProposal(userId, resolved, userCouponId, cartIds);
    }

    /**
     * 解析收货地址：显式传入的优先，否则取默认地址，再否则取最近新增的一条。
     * <p>
     * 用字符串列名而不是 lambda：{@code Address.isDefault} 字段的 getter 是 {@code isDefault()}，
     * MyBatis-Plus 的 lambda 解析会把它当成 {@code default} 属性，找不到对应列而抛异常。
     *
     * @param userId    当前用户 ID
     * @param addressId 显式指定地址，可为空
     * @return 可用地址，不会为空
     * @throws BusinessException 用户没有可用地址或地址不属于本人
     */
    private Address resolveAddress(Long userId, Long addressId) {
        Address address = resolveAddressOrNull(userId, addressId);
        if (address == null) {
            throw new BusinessException("还没有收货地址，请先在「我的-收货地址」添加");
        }
        return address;
    }

    /**
     * 解析收货地址，允许「一条都没有」。
     * <p>
     * 与 {@link #resolveAddress} 的差别只在「没有任何地址」这一种情况：
     * 生成提议时地址是硬前提，必须报错；而弹出勾选弹层时地址可以由用户在弹层里补，
     * 此时返回 null 让前端展示「请选择收货地址」。
     * <p>
     * 显式传入但归属不符的地址一律报错，不因为「允许为空」就放过越权。
     *
     * @param userId    当前用户 ID
     * @param addressId 显式指定地址，可为空
     * @return 可用地址；用户没有任何地址时返回 null
     * @throws BusinessException 显式传入的地址不存在或不属于本人
     */
    private Address resolveAddressOrNull(Long userId, Long addressId) {
        if (addressId != null) {
            Address address = addressService.getById(addressId);
            if (address == null || !Objects.equals(address.getMemberId(), userId)) {
                throw new BusinessException(ProposalRejectCode.ADDRESS_INVALID.getMessage());
            }
            return address;
        }
        List<Address> addresses = addressService.list(new QueryWrapper<Address>()
                .eq("member_id", userId)
                .orderByDesc("is_default")
                .orderByDesc("id")
                .last("LIMIT 1"));
        return addresses == null || addresses.isEmpty() ? null : addresses.get(0);
    }

    /**
     * 地址摘要，供弹层展示「这一单寄到哪」。手机号做脱敏，不下发完整号码。
     *
     * @param address 地址
     * @return 收件人 + 脱敏手机号 + 省市区 + 详细地址
     */
    private static String addressText(Address address) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(address.getName() == null ? "" : address.getName());
        String tel = maskTel(address.getTel());
        if (!tel.isBlank()) {
            sb.append(' ').append(tel);
        }
        appendIfPresent(sb, address.getProvince());
        appendIfPresent(sb, address.getCity());
        appendIfPresent(sb, address.getCounty());
        appendIfPresent(sb, address.getAddressDetail());
        return sb.toString().trim();
    }

    /**
     * 非空片段追加到摘要。
     *
     * @param sb    目标缓冲
     * @param value 片段
     */
    private static void appendIfPresent(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(' ').append(value.trim());
        }
    }

    /**
     * 手机号脱敏：保留前 3 位与后 4 位。
     *
     * @param tel 原始号码，可为空
     * @return 脱敏号码；无法脱敏时返回空串
     */
    private static String maskTel(String tel) {
        if (tel == null || tel.isBlank()) {
            return "";
        }
        String trimmed = tel.trim();
        if (trimmed.length() < 8) {
            return "";
        }
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    /**
     * 组装一条待确认提议实体。
     *
     * @param userId         用户 ID
     * @param addressId      收货地址 ID
     * @param userCouponId   优惠券 ID，可为空
     * @param carts          购物车列表
     * @param items          商品快照行
     * @param price          价格摘要
     * @param idempotencyKey 本次尝试的幂等键
     * @return 待插入的提议实体
     */
    private AiActionProposal newProposal(Long userId, Long addressId, Long userCouponId, List<Cart> carts,
                                         List<ProposalItem> items, PriceSummary price, String idempotencyKey) {
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
        return proposal;
    }

    /**
     * 判断既有提议能否直接复用。
     * <p>
     * 只有「待确认且在有效期内」才算可复用：其余状态要么已经下过单，
     * 要么已经作废，复用它们只会让用户点到一个注定失败的动作。
     *
     * @param proposal 既有提议
     * @return 可复用返回 true
     */
    private static boolean isReusable(AiActionProposal proposal) {
        if (proposal == null || !ProposalStatus.PENDING.name().equals(proposal.getStatus())) {
            return false;
        }
        return proposal.getExpireTime() == null || proposal.getExpireTime().isAfter(LocalDateTime.now());
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
            // detail 是定位问题的唯一线索：只说「购物车已变化」时，用户和支持都无从判断到底哪变了
            log.info("下单提议被拒绝 proposalNo={}, userId={}, reason={}, detail={}",
                    proposalNo, userId, e.getCode(), e.getDetail());
            return ConfirmResVO.failure(proposalNo, e.getCode());
        }

        try {
            SubmitOrderResVO resVO = submitThroughOrigin(proposal, userId);
            String orderSn = resVO.getOrderSn();
            // 先落订单号：即便下面等待超时，后续查询也能拿它回查真实落单结果
            proposalMapper.bindOrderSn(proposal.getId(), orderSn, LocalDateTime.now());

            /*
             * 原下单入口是异步的：它只做校验、算价、投递消息，订单由消息消费端落库。
             * 所以「入口返回成功」只代表消息发出去了，不代表订单已经存在。
             * 早期实现到这里就直接回「已下单」，消费端没启动时用户会拿到一个查不到的订单号，
             * 这正是「点了确认却没生成订单」的成因。现在必须在等待窗口内拿到落库结果才算成功。
             */
            String outcome = awaitSubmitResult(orderSn);
            if (outcome == null) {
                log.info("下单提议确认处理中，消费端尚未回结果 proposalNo={}, userId={}, orderSn={}",
                        proposalNo, userId, orderSn);
                // 金额仍以原入口计算值为准；前端据此继续轮询提议状态
                return ConfirmResVO.processing(proposalNo, orderSn, resVO.getActualPrice());
            }
            if (!SUBMIT_RESULT_SUCCESS.equals(outcome)) {
                log.info("下单提议落单失败 proposalNo={}, userId={}, orderSn={}, outcome={}",
                        proposalNo, userId, orderSn, outcome);
                proposalMapper.markRejected(proposal.getId(),
                        ProposalRejectCode.ORDER_SUBMIT_REJECTED.name(), LocalDateTime.now());
                return ConfirmResVO.failure(proposalNo, ProposalRejectCode.ORDER_SUBMIT_REJECTED,
                        meaningfulResult(outcome, ProposalRejectCode.ORDER_SUBMIT_REJECTED.getMessage()));
            }
            proposalMapper.markConfirmed(proposal.getId(), orderSn, LocalDateTime.now());
            log.info("下单提议确认成功 proposalNo={}, userId={}, orderSn={}", proposalNo, userId, orderSn);
            // 原入口返回的金额才是权威值：若与快照不一致说明运费/优惠规则有变，如实下发
            return ConfirmResVO.success(proposalNo, orderSn, resVO.getActualPrice());
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
        // 惰性回收：卡在「处理中」的提议在查询时补齐最终状态，前端轮询这里就能拿到答案
        if (ProposalStatus.CONFIRMING.name().equals(proposal.getStatus())) {
            reconcileConfirming(proposal);
        }
        return toView(proposal);
    }

    /**
     * 回收「处理中」提议的最终状态。
     * <p>
     * 确认接口等待窗口内没拿到落单结果时，提议会停在 CONFIRMING。
     * 这里按订单号回查结果缓存，把状态补齐成 CONFIRMED / REJECTED；
     * 结果缓存过期（60 秒）后仍无结果，说明消费端根本没工作，按超时拒绝并如实提示。
     * <p>
     * 本方法只读结果缓存并做条件更新，<b>不会触发任何下单动作</b>，可在查询接口里安全调用。
     *
     * @param proposal 当前提议，方法内会同步更新其状态字段
     */
    private void reconcileConfirming(AiActionProposal proposal) {
        String orderSn = proposal.getOrderSn();
        if (orderSn == null || orderSn.isBlank()) {
            // 没拿到订单号说明入口在投递前就中断了，交给下面的超时兜底
            applyConfirmTimeout(proposal);
            return;
        }
        String outcome;
        try {
            outcome = mobileOrderService.searchSubmitResult(orderSn);
        } catch (RuntimeException e) {
            // 查询结果失败不能连带让用户看不到提议，保持 CONFIRMING 等下次轮询
            log.warn("回查下单结果失败 proposalNo={}, orderSn={}", proposal.getProposalNo(), orderSn, e);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (SUBMIT_RESULT_SUCCESS.equals(outcome)) {
            proposalMapper.markConfirmed(proposal.getId(), orderSn, now);
            proposal.setStatus(ProposalStatus.CONFIRMED.name());
            proposal.setConfirmTime(now);
            return;
        }
        if (outcome != null) {
            proposalMapper.markRejected(proposal.getId(),
                    ProposalRejectCode.ORDER_SUBMIT_REJECTED.name(), now);
            proposal.setStatus(ProposalStatus.REJECTED.name());
            proposal.setFailCode(ProposalRejectCode.ORDER_SUBMIT_REJECTED.name());
            proposal.setConfirmTime(now);
            return;
        }
        applyConfirmTimeout(proposal);
    }

    /**
     * 处理中提议的超时兜底：超过配置时间仍无结果就落盘 REJECTED。
     *
     * @param proposal 当前提议，方法内会同步更新其状态字段
     */
    private void applyConfirmTimeout(AiActionProposal proposal) {
        LocalDateTime since = proposal.getUpdateTime();
        if (since == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (since.plusSeconds(properties.getConfirmTimeoutSeconds()).isAfter(now)) {
            return;
        }
        proposalMapper.markRejected(proposal.getId(), ProposalRejectCode.CONFIRM_TIMEOUT.name(), now);
        proposal.setStatus(ProposalStatus.REJECTED.name());
        proposal.setFailCode(ProposalRejectCode.CONFIRM_TIMEOUT.name());
        proposal.setConfirmTime(now);
        log.warn("下单提议确认超时 proposalNo={}, orderSn={}", proposal.getProposalNo(), proposal.getOrderSn());
    }

    /**
     * 在有限窗口内等待消费端写回落单结果。
     * <p>
     * 为什么值得同步等一下：确认是用户点了按钮的强交互，能当场给出「已创建 / 具体失败原因」
     * 远好过让前端再猜。窗口很短（默认 2 秒），实测落单在百毫秒级；超时就转成「处理中」。
     *
     * @param orderSn 订单号
     * @return success / 失败原因文本 / null（窗口内没有结果）
     */
    private String awaitSubmitResult(String orderSn) {
        if (orderSn == null || orderSn.isBlank()) {
            return null;
        }
        long waitMs = Math.max(0L, properties.getResultWaitMs());
        long intervalMs = Math.max(20L, properties.getResultPollIntervalMs());
        long deadline = System.currentTimeMillis() + waitMs;
        while (true) {
            String outcome = mobileOrderService.searchSubmitResult(orderSn);
            if (outcome != null) {
                return outcome;
            }
            if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                // 保留中断标记：请求线程被取消时应尽快退出，不要继续等
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /**
     * 把消费端写回的原始文本转成可展示文案。
     *
     * @param outcome       原始结果文本
     * @param fallbackText  没有有效文本时的兜底文案
     * @return 可展示文案
     */
    private static String meaningfulResult(String outcome, String fallbackText) {
        if (outcome == null || outcome.isBlank() || SUBMIT_RESULT_UNKNOWN.equals(outcome)) {
            return fallbackText;
        }
        return outcome;
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
            throw new ProposalRejectedException(ProposalRejectCode.ADDRESS_INVALID,
                    "addressId=" + proposal.getAddressId());
        }

        // 3) 购物车实时数据与快照逐项比对：价格、数量任一变化都拒绝，绝不按新价下单
        List<Long> cartIds = parseIds(proposal.getCartIds());
        List<Cart> liveCarts;
        try {
            liveCarts = resolveCarts(userId, cartIds);
        } catch (ProposalValidationException e) {
            /*
             * 提议生成后商品被删除、或用户主动取消勾选：按「内容已变化」拒绝，
             * 让用户重新生成提议。这里不能落到外层 RuntimeException 分支——
             * 那个分支会回滚成 PENDING 并提示「稍后重试」，而重试一万次结果都一样。
             */
            throw new ProposalRejectedException(e.getCode(), e.getDetail());
        }
        List<ProposalItem> liveItems = toItems(liveCarts);
        if (liveItems.size() != snapshot.size()) {
            throw new ProposalRejectedException(ProposalRejectCode.CART_CHANGED,
                    "快照 " + snapshot.size() + " 行，实时 " + liveItems.size() + " 行");
        }
        for (int i = 0; i < snapshot.size(); i++) {
            ProposalItem before = snapshot.get(i);
            ProposalItem after = liveItems.get(i);
            if (!Objects.equals(before.productId(), after.productId())
                    || !Objects.equals(before.number(), after.number())) {
                throw new ProposalRejectedException(ProposalRejectCode.CART_CHANGED,
                        "第 " + (i + 1) + " 行 快照=" + before + " 实时=" + after);
            }
            if (before.unitPrice().compareTo(after.unitPrice()) != 0) {
                throw new ProposalRejectedException(ProposalRejectCode.PRICE_CHANGED,
                        "第 " + (i + 1) + " 行 快照价=" + before.unitPrice() + " 实时价=" + after.unitPrice());
            }
        }

        // 4) 库存充足（只读查询，不冻结）
        for (ProposalItem item : liveItems) {
            GoodsProduct sku = goodsProductService.getById(item.productId());
            int available = sku == null || sku.getNumber() == null ? 0 : sku.getNumber();
            if (available < item.number()) {
                throw new ProposalRejectedException(ProposalRejectCode.STOCK_INSUFFICIENT,
                        "productId=" + item.productId() + " 可售=" + available + " 需要=" + item.number());
            }
        }

        // 5) 价格与优惠按实时数据重算，与快照比对
        PriceSummary live = calculatePrice(liveItems, proposal.getUserCouponId(), userId);
        if (live.actualPrice().compareTo(proposal.getActualPrice()) != 0
                || live.couponPrice().compareTo(proposal.getCouponPrice()) != 0) {
            throw new ProposalRejectedException(ProposalRejectCode.PRICE_CHANGED,
                    "快照应付=" + proposal.getActualPrice() + " 实时应付=" + live.actualPrice()
                            + " 快照优惠=" + proposal.getCouponPrice() + " 实时优惠=" + live.couponPrice());
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
     * 解析本次下单使用的购物车商品。
     * <p>
     * <b>这里是「未勾选商品混进订单」的唯一确定性拦截点。</b>三条规则缺一不可：
     * <ol>
     *   <li>不传 cartIds 时只取 {@code checked=true}——用户没点名商品，就完全按勾选框来；</li>
     *   <li>传了 cartIds 时，每一项都必须属于当前用户<b>且已勾选</b>。
     *       模型可能从历史对话里记住「用户想买保护壳」，而保护壳在购物车里并未勾选；
     *       勾选框是用户唯一的显式同意信号，服务端不认模型的推断，
     *       发现未勾选直接拒绝并回传商品名，让模型引导用户去勾选；</li>
     *   <li>行数据本身必须可下单（单价非空、数量为正），否则快照与金额计算会失真。</li>
     * </ol>
     *
     * @param userId  当前用户 ID
     * @param cartIds 购物车 ID；为空表示取全部已勾选
     * @return 购物车列表
     * @throws ProposalValidationException 选择无效、归属不符、未勾选或行数据不可下单
     */
    private List<Cart> resolveCarts(Long userId, List<Long> cartIds) {
        List<Long> requested = normalizeCartIds(cartIds);
        List<Cart> carts;
        if (requested.isEmpty()) {
            carts = cartService.list(Wrappers.lambdaQuery(Cart.class)
                    .eq(Cart::getChecked, true)
                    .eq(Cart::getUserId, userId));
        } else {
            carts = cartService.list(Wrappers.lambdaQuery(Cart.class)
                    .eq(Cart::getUserId, userId)
                    .in(Cart::getId, requested));
            if (carts == null || carts.size() != requested.size()) {
                // 数量不一致说明存在越权、失效或已删除购物车项，必须中断而不是部分下单
                throw new ProposalValidationException(ProposalRejectCode.CART_CHANGED,
                        "部分商品不在购物车中或已被删除");
            }
            List<Cart> unchecked = carts.stream()
                    .filter(cart -> !Boolean.TRUE.equals(cart.getChecked()))
                    .toList();
            if (!unchecked.isEmpty()) {
                throw new ProposalValidationException(ProposalRejectCode.CART_NOT_CHECKED,
                        "未勾选：" + namesOf(unchecked));
            }
        }
        if (carts == null || carts.isEmpty()) {
            throw new ProposalValidationException(ProposalRejectCode.CART_CHANGED,
                    "购物车没有已勾选的商品");
        }
        for (Cart cart : carts) {
            boolean orderable = cart.getId() != null && cart.getPrice() != null
                    && cart.getNumber() != null && cart.getNumber() > 0;
            if (!orderable) {
                // 价格或数量为空的脏数据不能进入快照：算出来的金额一定是错的
                throw new ProposalValidationException(ProposalRejectCode.CART_CHANGED,
                        "购物车商品数据异常，请重新加入购物车");
            }
        }
        return carts;
    }

    /**
     * 规整显式传入的购物车 ID：过滤 null、去重、封顶。
     *
     * @param cartIds 原始入参，可为 null
     * @return 可直接用于 IN 查询的 ID 列表；入参为空时返回空列表
     */
    private static List<Long> normalizeCartIds(List<Long> cartIds) {
        if (cartIds == null || cartIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = cartIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.size() > MAX_CART_IDS) {
            throw new ProposalValidationException(ProposalRejectCode.CART_CHANGED,
                    "一次最多提交 " + MAX_CART_IDS + " 种商品");
        }
        return distinct;
    }

    /**
     * 把购物车行拼成可读的商品名列表，供模型向用户复述。名称过长时截断，避免回灌占满上下文。
     *
     * @param carts 购物车行
     * @return 以顿号连接的名称，最多 5 个，其余以「等 N 件」收尾
     */
    private static String namesOf(List<Cart> carts) {
        int limit = 5;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < carts.size() && i < limit; i++) {
            if (sb.length() > 0) {
                sb.append('、');
            }
            String name = carts.get(i).getGoodsName();
            sb.append(name == null || name.isBlank() ? "未命名商品" : name);
        }
        if (carts.size() > limit) {
            sb.append(" 等 ").append(carts.size()).append(" 件");
        }
        return sb.toString();
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
    /**
     * 构建幂等键。
     * <p>
     * {@code attempt=0} 时不加后缀，保持与历史数据的兼容；
     * 之后每次重试换一个后缀，使「同一快照的第二次提议」有独立键，
     * 从而在保留唯一约束的前提下允许用户重新下单。
     *
     * @param userId       用户 ID
     * @param addressId    地址 ID
     * @param userCouponId 优惠券 ID，可为空
     * @param items        商品行
     * @param attempt      尝试序号，0 表示首次
     * @return 43 位 base64url 摘要
     */
    private String buildIdempotencyKey(Long userId, Long addressId, Long userCouponId,
                                       List<ProposalItem> items, int attempt) {
        String fingerprint = items.stream()
                .map(item -> item.productId() + ":" + item.number() + ":" + item.unitPrice().toPlainString())
                .sorted()
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        String source = userId + ":" + addressId + ":" + userCouponId + ":" + fingerprint
                + (attempt <= 0 ? "" : "#" + attempt);
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
     * 提议展示用的收货地址摘要。
     * <p>
     * 用户在点「确认下单」之前必须能看到「这一单寄到哪」，否则等于盲签：
     * 地址是确认决策的一部分，不是可选装饰。这里只给摘要（收件人 + 脱敏手机号 + 省市区门牌），
     * 手机号不下发原值。
     * <p>
     * 查询失败或地址在提议生成后被删除时返回 null，由前端提示「收货地址已失效，请重新选择」；
     * 视图组装不能因为一个展示字段失败就整体抛错，那样用户连金额和商品都看不到。
     *
     * @param addressId 提议快照里的地址 ID，可为空
     * @return 地址摘要；无法解析时为 null
     */
    private String resolveAddressText(Long addressId) {
        if (addressId == null) {
            return null;
        }
        try {
            Address address = addressService.getById(addressId);
            return address == null ? null : addressText(address);
        } catch (RuntimeException e) {
            log.warn("提议地址解析失败 addressId={} error={}", addressId, e.getMessage());
            return null;
        }
    }

    /**
     * 实体转对外视图。刻意不暴露主键、幂等键与联系方式原值（地址只给脱敏摘要）。
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
            case SUPERSEDED -> ProposalRejectCode.SUPERSEDED.getMessage();
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
                resolveAddressText(proposal.getAddressId()),
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
     * <p>
     * {@code detail} 只用于日志：它对定位「到底哪一项变了」很关键，
     * 但不能回给用户（里面带价格、商品 ID 等内部信息）。
     */
    private static final class ProposalRejectedException extends RuntimeException {

        private final transient ProposalRejectCode code;

        private final transient String detail;

        private ProposalRejectedException(ProposalRejectCode code) {
            this(code, null);
        }

        private ProposalRejectedException(ProposalRejectCode code, String detail) {
            super(code.getMessage());
            this.code = code;
            this.detail = detail;
        }

        private ProposalRejectCode getCode() {
            return code;
        }

        private String getDetail() {
            return detail == null ? "-" : detail;
        }
    }
}
