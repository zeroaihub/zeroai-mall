package com.zeroai.message.consumer.order;

import com.rabbitmq.client.Channel;
import com.zeroai.data.redis.constant.RedisKeyEnum;
import com.zeroai.message.consumer.client.mobile.MobileApi;
import com.zeroai.message.consumer.support.AbstractSingleMessageConsumer;
import com.zeroai.message.consumer.support.MessageConsumerSupport;
import com.zeroai.message.core.constant.MQConstants;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static com.zeroai.data.redis.constant.RedisKeyEnum.SECKILL_ORDER_CONSUMER_MAP;

/**
 * 秒杀异步落单消费入口。
 * 秒杀订单与普通订单队列隔离，活动高峰时可以独立扩容消费者并降低普通订单链路互相影响。
 */
@Component
public class SeckillOrderConsumer extends AbstractSingleMessageConsumer {

    private final MobileApi mobileApi;

    /**
     * 构造秒杀落单消费者。
     *
     * @param messageConsumerSupport MQ 消费支撑服务
     * @param mobileApi mobile 回调客户端
     */
    public SeckillOrderConsumer(MessageConsumerSupport messageConsumerSupport, MobileApi mobileApi) {
        super(messageConsumerSupport);
        this.mobileApi = mobileApi;
    }

    /**
     * 处理秒杀落单消息。
     *
     * @param channel RabbitMQ 通道
     * @param message RabbitMQ 消息
     * @throws IOException ack/nack 通道异常
     */
    @RabbitListener(queues = MQConstants.SECKILL_ORDER_DIRECT_QUEUE)
    public void process(Channel channel, Message message) throws IOException {
        consume(channel, message);
    }

    /**
     * 返回消费者名称。
     *
     * @return 消费者名称
     */
    @Override
    protected String consumerName() {
        return "SeckillOrderConsumer";
    }

    /**
     * 返回秒杀落单消费幂等 Key。
     *
     * @return Redis 幂等 Key 枚举
     */
    @Override
    protected RedisKeyEnum redisKeyEnum() {
        return SECKILL_ORDER_CONSUMER_MAP;
    }

    /**
     * 调用 mobile 秒杀落单回调。
     *
     * @param body UTF-8 消息体
     * @throws Exception 回调失败
     */
    @Override
    protected void handle(String body) throws Exception {
        mobileApi.submitSeckillOrder(body);
    }
}
