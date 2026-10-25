package com.zeroai.message.core.config;

import com.zeroai.message.core.constant.MQConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 业务配置
 */
@Configuration
public class BusinessRabbitConfig {

    /************************************ 邮件队列、交换机 begin *******************************************/

    /**
     * 设置队列名称，并持久化
     *
     * @return 队列
     */
    @Bean
    public Queue emailDirectQueue() {
        return new Queue(MQConstants.EMAIL_DIRECT_QUEUE);
    }

    /**
     * 设置交换机名称
     *
     * @return 交换机
     */
    @Bean
    DirectExchange emailDirectExchange() {
        return new DirectExchange(MQConstants.EMAIL_DIRECT_EXCHANGE);
    }

    /**
     * 将队列和交换机绑定, 并设置用于匹配键：EmailDirectRouting
     */
    @Bean
    Binding bindingEmailDirect() {
        return BindingBuilder.bind(emailDirectQueue()).to(emailDirectExchange()).with(MQConstants.EMAIL_DIRECT_ROUTING);
    }
    /************************************ 订单队列、交换机 end *******************************************/


    /************************************ 订单队列、交换机 begin *******************************************/
    @Bean
    public Queue orderDirectQueue() {

        Map<String, Object> params = new HashMap<>();
        // 声明当前队列绑定的死信交换机
        params.put("x-dead-letter-exchange", MQConstants.DL_TOPIC_EXCHANGE);
        // // 声明当前队列的死信路由键
        params.put("x-dead-letter-routing-key", MQConstants.DL_ROUTING_KEY);
        return new Queue(MQConstants.ORDER_DIRECT_QUEUE, true, false, false, params);
    }

    @Bean
    DirectExchange orderDirectExchange() {
        return new DirectExchange(MQConstants.ORDER_DIRECT_EXCHANGE);
    }

    @Bean
    Binding bindingOrderDirect() {
        return BindingBuilder.bind(orderDirectQueue()).to(orderDirectExchange()).with(MQConstants.ORDER_DIRECT_ROUTING);
    }
    /************************************ 订单队列、交换机 end *******************************************/

    /************************************ 秒杀订单队列、交换机 begin *******************************************/
    /**
     * 秒杀异步落单队列。
     * 和普通订单队列隔离，避免活动高峰导致普通下单消息消费延迟。
     *
     * @return 秒杀订单队列
     */
    @Bean
    public Queue seckillOrderDirectQueue() {
        Map<String, Object> params = new HashMap<>();
        params.put("x-dead-letter-exchange", MQConstants.DL_TOPIC_EXCHANGE);
        params.put("x-dead-letter-routing-key", MQConstants.DL_ROUTING_KEY);
        return new Queue(MQConstants.SECKILL_ORDER_DIRECT_QUEUE, true, false, false, params);
    }

    /**
     * 秒杀异步落单交换机。
     *
     * @return 秒杀订单交换机
     */
    @Bean
    DirectExchange seckillOrderDirectExchange() {
        return new DirectExchange(MQConstants.SECKILL_ORDER_DIRECT_EXCHANGE);
    }

    /**
     * 绑定秒杀订单队列和交换机。
     *
     * @return 秒杀订单绑定关系
     */
    @Bean
    Binding bindingSeckillOrderDirect() {
        return BindingBuilder.bind(seckillOrderDirectQueue())
                .to(seckillOrderDirectExchange())
                .with(MQConstants.SECKILL_ORDER_DIRECT_ROUTING);
    }
    /************************************ 秒杀订单队列、交换机 end *******************************************/

}
