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

import static com.zeroai.data.redis.constant.RedisKeyEnum.SECKILL_UNPAID_ORDER_CONSUMER_MAP;

/**
 * 秒杀未支付订单延迟关单消费入口。
 * 秒杀订单 60 秒释放库存要求更高，使用独立延迟队列避免和普通订单长时间关单策略互相耦合。
 */
@Component
public class SeckillOrderDelayConsumer extends AbstractSingleMessageConsumer {

    private final MobileApi mobileApi;

    /**
     * 构造秒杀未支付关单消费者。
     *
     * @param messageConsumerSupport MQ 消费支撑服务
     * @param mobileApi mobile 回调客户端
     */
    public SeckillOrderDelayConsumer(MessageConsumerSupport messageConsumerSupport, MobileApi mobileApi) {
        super(messageConsumerSupport);
        this.mobileApi = mobileApi;
    }

    /**
     * 处理秒杀未支付关单消息。
     *
     * @param channel RabbitMQ 通道
     * @param message RabbitMQ 消息
     * @throws IOException ack/nack 通道异常
     */
    @RabbitListener(queues = MQConstants.SECKILL_ORDER_DELAY_QUEUE)
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
        return "SeckillOrderDelayConsumer";
    }

    /**
     * 返回秒杀未支付关单消费幂等 Key。
     *
     * @return Redis 幂等 Key 枚举
     */
    @Override
    protected RedisKeyEnum redisKeyEnum() {
        return SECKILL_UNPAID_ORDER_CONSUMER_MAP;
    }

    /**
     * 调用 mobile 秒杀未支付关单回调。
     *
     * @param body UTF-8 消息体
     * @throws Exception 回调失败
     */
    @Override
    protected void handle(String body) throws Exception {
        mobileApi.unpaidSeckillOrder(body);
    }
}
