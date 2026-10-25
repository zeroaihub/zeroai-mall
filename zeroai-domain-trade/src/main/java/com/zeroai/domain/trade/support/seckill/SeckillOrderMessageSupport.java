package com.zeroai.domain.trade.support.seckill;

import com.alibaba.fastjson.JSON;
import com.zeroai.domain.api.common.ZeroaiConfig;
import com.zeroai.domain.api.outbox.service.LocalMessageTopics;
import com.zeroai.domain.trade.outbox.LocalMessageCreateCommand;
import com.zeroai.domain.trade.outbox.LocalMessageService;
import com.zeroai.message.core.constant.MQConstants;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 秒杀订单消息支撑服务。
 * 异步落单走本地消息表保证可靠投递；60 秒未支付关单在订单事务提交后直接发延迟 MQ，满足秒杀库存快速释放诉求。
 */
@Slf4j
@Service
public class SeckillOrderMessageSupport {

    private static final int SECKILL_UNPAID_DELAY_MILLIS = 60_000;
    private static final String SECKILL_ORDER_SUBMIT_KEY_PREFIX = "SECKILL_ORDER_SUBMIT:";
    private static final String SECKILL_ORDER_FIELD = "order";
    private static final String ORDER_SN_FIELD = "orderSn";
    private static final String NOTIFY_URL_FIELD = "notifyUrl";
    private static final String SECKILL_ORDER_SUBMIT_CALLBACK_PATH = "/callback/seckill/order/submit";
    private static final String SECKILL_ORDER_UNPAID_CALLBACK_PATH = "/callback/seckill/order/unpaid";

    private final LocalMessageService localMessageService;
    private final RabbitTemplate delayRabbitTemplate;

    /**
     * 构造秒杀订单消息支撑服务。
     *
     * @param localMessageService 本地消息服务
     * @param delayRabbitTemplate 延迟 RabbitMQ 模板
     */
    public SeckillOrderMessageSupport(LocalMessageService localMessageService,
                                      @Qualifier("delayRabbitTemplate") RabbitTemplate delayRabbitTemplate) {
        this.localMessageService = localMessageService;
        this.delayRabbitTemplate = delayRabbitTemplate;
    }

    /**
     * 保存秒杀异步落单本地消息。
     * 本地消息表作为可靠投递 outbox，避免秒杀入口 Redis 扣减成功但 MQ 瞬断导致订单永远不创建。
     *
     * @param message 秒杀落单消息
     */
    public void saveSubmitMessage(SeckillOrderSubmitMessage message) {
        if (message == null || StringUtils.isBlank(message.getOrderSn())) {
            log.warn("跳过秒杀下单消息保存，消息体非法，message={}", message);
            return;
        }
        localMessageService.saveMessage(LocalMessageCreateCommand.builder()
                .messageKey(SECKILL_ORDER_SUBMIT_KEY_PREFIX + message.getOrderSn())
                .topic(LocalMessageTopics.SECKILL_ORDER_SUBMIT)
                .bizType(LocalMessageTopics.BIZ_TYPE_ORDER)
                .bizId(message.getOrderSn())
                .exchangeName(MQConstants.SECKILL_ORDER_DIRECT_EXCHANGE)
                .routingKey(MQConstants.SECKILL_ORDER_DIRECT_ROUTING)
                .payload(JSON.toJSONString(buildSubmitPayload(message)))
                .build());
    }

    /**
     * 直接发送秒杀未支付延迟关单消息。
     * 只在订单落库事务提交后调用；如果发送失败，消费者重试落单消息时会因订单已存在再次补发。
     *
     * @param orderSn 订单号
     */
    public void sendUnpaidDelayMessage(String orderSn) {
        if (StringUtils.isBlank(orderSn)) {
            log.warn("跳过秒杀未支付延迟消息发送，orderSn为空");
            return;
        }
        Message rabbitMessage = MessageBuilder.withBody(JSON.toJSONString(buildUnpaidPayload(orderSn))
                        .getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId("SECKILL_ORDER_UNPAID_DELAY:" + orderSn)
                .build();
        delayRabbitTemplate.convertAndSend(MQConstants.ORDER_DELAY_EXCHANGE,
                MQConstants.SECKILL_ORDER_DELAY_ROUTING,
                rabbitMessage,
                messagePostProcessor -> {
                    messagePostProcessor.getMessageProperties().setDelayLong((long) SECKILL_UNPAID_DELAY_MILLIS);
                    return messagePostProcessor;
                });
    }

    /**
     * 构建秒杀落单消息体。
     *
     * @param message 秒杀落单消息
     * @return MQ 消息体
     */
    private Map<String, Object> buildSubmitPayload(SeckillOrderSubmitMessage message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(SECKILL_ORDER_FIELD, message);
        payload.put(NOTIFY_URL_FIELD, buildMobileCallbackUrl(SECKILL_ORDER_SUBMIT_CALLBACK_PATH));
        return payload;
    }

    /**
     * 构建秒杀未支付关单消息体。
     *
     * @param orderSn 订单号
     * @return MQ 消息体
     */
    private Map<String, Object> buildUnpaidPayload(String orderSn) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(ORDER_SN_FIELD, orderSn);
        payload.put(NOTIFY_URL_FIELD, buildMobileCallbackUrl(SECKILL_ORDER_UNPAID_CALLBACK_PATH));
        return payload;
    }

    /**
     * 构建 mobile 回调地址。
     *
     * @param path 回调路径
     * @return 完整回调地址
     */
    private String buildMobileCallbackUrl(String path) {
        return ZeroaiConfig.getMobileUrl() + path;
    }
}
