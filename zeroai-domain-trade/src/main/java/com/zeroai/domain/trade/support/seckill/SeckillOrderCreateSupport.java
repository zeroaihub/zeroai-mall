package com.zeroai.domain.trade.support.seckill;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zeroai.data.redis.manager.RedisCache;
import com.zeroai.domain.api.cart.entity.Cart;
import com.zeroai.domain.api.common.ZeroaiConfig;
import com.zeroai.domain.api.goods.entity.Goods;
import com.zeroai.domain.api.goods.entity.GoodsProduct;
import com.zeroai.domain.api.goods.service.IGoodsProductService;
import com.zeroai.domain.api.goods.service.IGoodsService;
import com.zeroai.domain.api.promotion.entity.SeckillSku;
import com.zeroai.domain.api.promotion.enums.SeckillActivityStatusEnum;
import com.zeroai.domain.api.promotion.service.ISeckillSkuService;
import com.zeroai.domain.api.trade.entity.Address;
import com.zeroai.domain.api.trade.entity.Order;
import com.zeroai.domain.api.trade.entity.OrderActivityRelation;
import com.zeroai.domain.api.trade.entity.OrderGoods;
import com.zeroai.domain.api.trade.enums.OrderActivityInventoryStatusEnum;
import com.zeroai.domain.api.trade.enums.OrderActivityTypeEnum;
import com.zeroai.domain.api.trade.mapper.OrderMapper;
import com.zeroai.domain.api.trade.service.IAddressService;
import com.zeroai.domain.api.trade.service.IOrderActivityRelationService;
import com.zeroai.domain.api.trade.service.IOrderGoodsService;
import com.zeroai.domain.inventory.support.OrderStockSupport;
import com.zeroai.domain.promotion.support.seckill.SeckillRedisKeySupport;
import com.zeroai.domain.promotion.support.seckill.SeckillResultSupport;
import com.zeroai.util.enums.OrderStatusEnum;
import com.zeroai.util.enums.ReturnCodeEnum;
import com.zeroai.util.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 秒杀订单落库支撑服务。
 * 消费端调用该类创建统一订单，并在同一事务内完成普通 SKU 冻结、秒杀活动库存冻结和订单活动关联写入。
 */
@Slf4j
@Service
@AllArgsConstructor
public class SeckillOrderCreateSupport {

    private final OrderMapper orderMapper;
    private final IOrderGoodsService orderGoodsService;
    private final IOrderActivityRelationService orderActivityRelationService;
    private final IAddressService addressService;
    private final IGoodsService goodsService;
    private final IGoodsProductService goodsProductService;
    private final ISeckillSkuService seckillSkuService;
    private final OrderStockSupport orderStockSupport;
    private final SeckillOrderMessageSupport seckillOrderMessageSupport;
    private final SeckillResultSupport seckillResultSupport;
    private final RedisCache redisCache;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建秒杀订单。
     * 订单已存在时直接补发 60 秒关单消息并返回，保证 MQ 重试不会重复落库。
     *
     * @param orderJson 秒杀异步落单消息 JSON
     */
    public void create(String orderJson) {
        SeckillOrderSubmitMessage message = parseMessage(orderJson);
        boolean orderCreatedOrExists = false;
        try {
            orderCreatedOrExists = Boolean.TRUE.equals(transactionTemplate.execute(status -> doCreate(message)));
            seckillOrderMessageSupport.sendUnpaidDelayMessage(message.getOrderSn());
        } catch (RuntimeException e) {
            if (!orderCreatedOrExists) {
                // 只有订单未落库时才回滚 Redis 入口库存；订单已存在但补发延迟消息失败不能释放库存。
                releaseEntranceStock(message);
                seckillResultSupport.markFailed(message.getOrderSn(), e.getMessage());
            }
            throw e;
        }
    }

    /**
     * 在事务内创建秒杀订单。
     *
     * @param message 秒杀落单消息
     * @return true=订单已存在或创建成功
     */
    private Boolean doCreate(SeckillOrderSubmitMessage message) {
        validateMessage(message);
        if (existsOrder(message.getOrderSn())) {
            seckillResultSupport.markSuccess(message.getOrderSn(), null);
            return true;
        }
        SeckillSku sku = requireSku(message.getActivitySkuId());
        validateUserPurchaseLimit(message, sku);
        Address address = requireAddress(message.getAddressId(), message.getUserId());
        Goods goods = requireGoods(sku.getGoodsId());
        GoodsProduct product = requireProduct(sku.getProductId(), sku.getGoodsId());
        Cart cartSnapshot = buildCartSnapshot(message, sku, goods, product);
        BigDecimal goodsPrice = sku.getSeckillPrice().multiply(BigDecimal.valueOf(message.getNumber()));
        BigDecimal freightPrice = calculateFreightPrice(goodsPrice);
        BigDecimal actualPrice = goodsPrice.add(freightPrice).max(BigDecimal.ZERO);

        // 普通 SKU 冻结库存和库存流水仍是 MySQL 最终兜底，Redis 秒杀库存只负责入口削峰。
        orderStockSupport.freezeStock(message.getOrderSn(), List.of(cartSnapshot));
        freezeSeckillActivityStock(sku.getId(), message.getNumber());

        Order order = buildOrder(message, address, goodsPrice, freightPrice, actualPrice);
        if (orderMapper.insert(order) != 1) {
            throw new BusinessException(ReturnCodeEnum.ORDER_SUBMIT_ERROR);
        }
        OrderGoods orderGoods = buildOrderGoods(order.getId(), cartSnapshot);
        if (!orderGoodsService.save(orderGoods)) {
            throw new BusinessException(ReturnCodeEnum.ORDER_SUBMIT_ERROR);
        }
        if (!orderActivityRelationService.save(buildRelation(message, sku, order, orderGoods))) {
            throw new BusinessException(ReturnCodeEnum.ORDER_SUBMIT_ERROR);
        }
        seckillResultSupport.markSuccess(message.getOrderSn(), actualPrice);
        return true;
    }

    /**
     * 解析秒杀落单消息。
     *
     * @param orderJson 秒杀落单 JSON
     * @return 秒杀落单消息
     */
    private SeckillOrderSubmitMessage parseMessage(String orderJson) {
        if (StringUtils.isBlank(orderJson)) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_MISS_ERROR);
        }
        return JSON.parseObject(orderJson, SeckillOrderSubmitMessage.class);
    }

    /**
     * 校验秒杀落单消息。
     *
     * @param message 秒杀落单消息
     */
    private void validateMessage(SeckillOrderSubmitMessage message) {
        if (message == null || StringUtils.isBlank(message.getOrderSn()) || message.getUserId() == null
                || message.getAddressId() == null || message.getActivitySkuId() == null
                || message.getNumber() == null || message.getNumber() <= 0) {
            throw new BusinessException(ReturnCodeEnum.PARAMETER_MISS_ERROR);
        }
    }

    /**
     * 判断订单是否已存在。
     *
     * @param orderSn 订单号
     * @return true=已存在
     */
    private boolean existsOrder(String orderSn) {
        return orderMapper.selectOne(Wrappers.lambdaQuery(Order.class)
                .select(Order::getId)
                .eq(Order::getOrderSn, orderSn)
                .last("limit 1")) != null;
    }

    /**
     * 查询并校验活动 SKU。
     *
     * @param activitySkuId 活动 SKU ID
     * @return 活动 SKU
     */
    private SeckillSku requireSku(Long activitySkuId) {
        SeckillSku sku = seckillSkuService.getById(activitySkuId);
        if (sku == null || !Objects.equals(sku.getStatus(), SeckillActivityStatusEnum.PUBLISHED.getStatus())) {
            throw new BusinessException(ReturnCodeEnum.ORDER_SUBMIT_ERROR, "秒杀商品不可抢购");
        }
        return sku;
    }

    /**
     * 校验用户活动商品购买限制。
     * 消费端事务内再查一次持久化购买记录，防止异常消息、历史 Redis 标记失效或人工重投绕过入口拦截。
     *
     * @param message 秒杀落单消息
     * @param sku 活动 SKU
     */
    private void validateUserPurchaseLimit(SeckillOrderSubmitMessage message, SeckillSku sku) {
        if (orderActivityRelationService.existsActiveSeckillPurchase(sku.getActivityId(), sku.getGoodsId(), message.getUserId())) {
            throw new BusinessException(ReturnCodeEnum.ORDER_SUBMIT_ERROR, "请勿重复购买该活动商品");
        }
    }

    /**
     * 查询并校验收货地址。
     *
     * @param addressId 地址 ID
     * @param userId 用户 ID
     * @return 收货地址
     */
    private Address requireAddress(Long addressId, Long userId) {
        Address address = addressService.getById(addressId);
        if (address == null || !Objects.equals(address.getMemberId(), userId)) {
            throw new BusinessException(ReturnCodeEnum.ORDER_ERROR_ADDRESS_ERROR);
        }
        return address;
    }

    /**
     * 查询并校验商品。
     *
     * @param goodsId 商品 ID
     * @return 商品
     */
    private Goods requireGoods(Long goodsId) {
        Goods goods = goodsService.getById(goodsId);
        if (goods == null) {
            throw new BusinessException(ReturnCodeEnum.GOODS_HAS_OFFSHELF_ERROR);
        }
        return goods;
    }

    /**
     * 查询并校验商品货品。
     *
     * @param productId 货品 ID
     * @param goodsId 商品 ID
     * @return 商品货品
     */
    private GoodsProduct requireProduct(Long productId, Long goodsId) {
        GoodsProduct product = goodsProductService.getById(productId);
        if (product == null || !Objects.equals(product.getGoodsId(), goodsId)) {
            throw new BusinessException(ReturnCodeEnum.GOODS_STOCK_NOT_ENOUGH_ERROR);
        }
        return product;
    }

    /**
     * 冻结秒杀活动库存。
     *
     * @param activitySkuId 活动 SKU ID
     * @param number 冻结数量
     */
    private void freezeSeckillActivityStock(Long activitySkuId, Integer number) {
        boolean updated = seckillSkuService.update(Wrappers.lambdaUpdate(SeckillSku.class)
                .setSql("available_stock = available_stock - " + number)
                .setSql("locked_stock = locked_stock + " + number)
                .set(SeckillSku::getUpdateTime, new Date())
                .eq(SeckillSku::getId, activitySkuId)
                .ge(SeckillSku::getAvailableStock, number));
        if (!updated) {
            throw new BusinessException(ReturnCodeEnum.ORDER_SUBMIT_ERROR, "秒杀活动库存不足");
        }
    }

    /**
     * 构建购物车快照以复用普通库存冻结逻辑。
     *
     * @param message 秒杀落单消息
     * @param sku 活动 SKU
     * @param goods 商品
     * @param product 商品货品
     * @return 购物车快照
     */
    private Cart buildCartSnapshot(SeckillOrderSubmitMessage message, SeckillSku sku, Goods goods, GoodsProduct product) {
        Cart cart = new Cart();
        cart.setUserId(Math.toIntExact(message.getUserId()));
        cart.setGoodsId(sku.getGoodsId());
        cart.setGoodsSn(goods.getGoodsSn());
        cart.setGoodsName(goods.getName());
        cart.setProductId(sku.getProductId());
        cart.setPrice(sku.getSeckillPrice());
        cart.setNumber(message.getNumber());
        cart.setSpecifications(product.getSpecifications());
        cart.setPicUrl(StringUtils.defaultIfBlank(product.getUrl(), goods.getPicUrl()));
        cart.setChecked(Boolean.TRUE);
        return cart;
    }

    /**
     * 构建订单主表。
     *
     * @param message 秒杀落单消息
     * @param address 收货地址
     * @param goodsPrice 商品金额
     * @param freightPrice 运费
     * @param actualPrice 实付金额
     * @return 订单主表
     */
    private Order buildOrder(SeckillOrderSubmitMessage message, Address address, BigDecimal goodsPrice,
                             BigDecimal freightPrice, BigDecimal actualPrice) {
        Order order = new Order();
        order.setUserId(message.getUserId());
        order.setOrderSn(message.getOrderSn());
        order.setOrderStatus(OrderStatusEnum.STATUS_CREATE.getStatus());
        order.setConsignee(address.getName());
        order.setMobile(address.getTel());
        order.setAddress(address.getProvince() + address.getCity() + address.getCounty() + " " + address.getAddressDetail());
        order.setMessage(StringUtils.defaultString(message.getMessage()));
        order.setGoodsPrice(goodsPrice);
        order.setFreightPrice(freightPrice);
        order.setCouponPrice(BigDecimal.ZERO);
        order.setOrderPrice(goodsPrice.add(freightPrice));
        order.setActualPrice(actualPrice);
        order.setCreateTime(new Date());
        order.setUpdateTime(new Date());
        order.setDelFlag(Boolean.FALSE);
        return order;
    }

    /**
     * 构建订单商品明细。
     *
     * @param orderId 订单 ID
     * @param cartSnapshot 购物车快照
     * @return 订单商品明细
     */
    private OrderGoods buildOrderGoods(Long orderId, Cart cartSnapshot) {
        OrderGoods orderGoods = new OrderGoods();
        orderGoods.setOrderId(orderId);
        orderGoods.setGoodsId(cartSnapshot.getGoodsId());
        orderGoods.setGoodsSn(cartSnapshot.getGoodsSn());
        orderGoods.setProductId(cartSnapshot.getProductId());
        orderGoods.setGoodsName(cartSnapshot.getGoodsName());
        orderGoods.setPicUrl(cartSnapshot.getPicUrl());
        orderGoods.setPrice(cartSnapshot.getPrice());
        orderGoods.setNumber(cartSnapshot.getNumber());
        orderGoods.setSpecifications(cartSnapshot.getSpecifications());
        orderGoods.setCreateTime(LocalDateTime.now());
        orderGoods.setUpdateTime(LocalDateTime.now());
        orderGoods.setDelFlag(Boolean.FALSE);
        return orderGoods;
    }

    /**
     * 构建订单活动关联。
     *
     * @param message 秒杀落单消息
     * @param sku 活动 SKU
     * @param order 订单主表
     * @param orderGoods 订单商品明细
     * @return 订单活动关联
     */
    private OrderActivityRelation buildRelation(SeckillOrderSubmitMessage message, SeckillSku sku,
                                                Order order, OrderGoods orderGoods) {
        Date now = new Date();
        OrderActivityRelation relation = new OrderActivityRelation();
        relation.setOrderId(order.getId());
        relation.setOrderSn(order.getOrderSn());
        relation.setOrderGoodsId(orderGoods.getId());
        relation.setUserId(message.getUserId());
        relation.setActivityType(OrderActivityTypeEnum.SECKILL.getType());
        relation.setActivityId(sku.getActivityId());
        relation.setActivitySkuId(sku.getId());
        relation.setGoodsId(sku.getGoodsId());
        relation.setProductId(sku.getProductId());
        relation.setActivityPrice(sku.getSeckillPrice());
        relation.setNumber(message.getNumber());
        relation.setInventoryStatus(OrderActivityInventoryStatusEnum.LOCKED.getStatus());
        relation.setCreateTime(now);
        relation.setUpdateTime(now);
        relation.setDelFlag(Boolean.FALSE);
        return relation;
    }

    /**
     * 计算运费。
     *
     * @param goodsPrice 商品金额
     * @return 运费
     */
    private BigDecimal calculateFreightPrice(BigDecimal goodsPrice) {
        return goodsPrice.compareTo(ZeroaiConfig.getFreightLimit()) < 0
                ? ZeroaiConfig.getFreightPrice()
                : BigDecimal.ZERO;
    }

    /**
     * 回滚 Redis 入口库存和用户抢购标记。
     *
     * @param message 秒杀落单消息
     */
    private void releaseEntranceStock(SeckillOrderSubmitMessage message) {
        if (message == null || message.getActivitySkuId() == null || message.getActivityId() == null
                || message.getGoodsId() == null || message.getUserId() == null || message.getNumber() == null) {
            return;
        }
        redisCache.incrementCacheObject(SeckillRedisKeySupport.stockKey(message.getActivitySkuId()), message.getNumber());
        redisCache.deleteObject(SeckillRedisKeySupport.userPurchaseKey(message.getActivityId(), message.getGoodsId(), message.getUserId()));
        redisCache.deleteObject(SeckillRedisKeySupport.resultKey(message.getOrderSn()));
    }
}
