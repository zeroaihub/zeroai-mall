package com.zeroai.message.consumer.client.mobile;

import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

/**
 * mobile服务调用api
 *
 * @date: 2023/8/20 18:30
 */
public interface MobileApi {
    void submitOrder(String body) throws Exception;

    @Retryable(value = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 2000L, multiplier = 1.5))
    void submitSeckillOrder(String body) throws Exception;

    @Retryable(value = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 2000L, multiplier = 1.5))
    void unpaidOrder(String body) throws Exception;

    @Retryable(value = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 2000L, multiplier = 1.5))
    void unpaidSeckillOrder(String body) throws Exception;

    @Retryable(value = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 2000L, multiplier = 1.5))
    void sendEmail(String body) throws Exception;
}
