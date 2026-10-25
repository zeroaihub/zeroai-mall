package com.zeroai.message.consumer.client.mobile;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.zeroai.message.core.constant.MQConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.*;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * @date: 2023/8/20 18:30
 */
@Slf4j
@Service
public class MobileApiImpl implements MobileApi {
    @Resource
    private RestTemplate restTemplate;

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 2000L, multiplier = 1.5))
    @Override
    public void submitOrder(String body) throws Exception {
        JSONObject msgObject = JSONObject.parseObject(body);
        String notifyUrl = (String) msgObject.get("notifyUrl");
        if (StringUtils.isEmpty(notifyUrl)) {
            throw new Exception("获取mobile下单api失败，notifyUrl为空");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> multiValueMap = new LinkedMultiValueMap<>();
        multiValueMap.add("order", msgObject.get("order"));
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(multiValueMap, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(notifyUrl, request, String.class);
        log.info("submitOrder response:{}", response.getBody());
        if (response.getStatusCode().value() != HttpStatus.OK.value()) {
            throw new Exception("调用mobile下单api失败， body：" + body);
        }
        JSONObject jsonObject = JSONObject.parseObject(response.getBody());
        if (jsonObject != null && MQConstants.RESULT_SUCCESS_CODE != jsonObject.getInteger("code")) {
            throw new Exception("调用mobile下单api失败， resp：" + jsonObject);
        }
    }

    /**
     * 调用 mobile 秒杀落单回调。
     *
     * @param body MQ 消息体
     * @throws Exception 回调失败
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 2000L, multiplier = 1.5))
    @Override
    public void submitSeckillOrder(String body) throws Exception {
        JSONObject msgObject = JSONObject.parseObject(body);
        String notifyUrl = (String) msgObject.get("notifyUrl");
        if (StringUtils.isEmpty(notifyUrl)) {
            throw new Exception("获取mobile秒杀下单api失败，notifyUrl为空");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> multiValueMap = new LinkedMultiValueMap<>();
        multiValueMap.add("order", msgObject.get("order"));
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(multiValueMap, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(notifyUrl, request, String.class);
        log.info("submitSeckillOrder response:{}", response.getBody());
        validateResponse(response, body, "调用mobile秒杀下单api失败");
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 2000L, multiplier = 1.5))
    @Override
    public void unpaidOrder(String body) throws Exception {
        JSONObject msgObject = JSONObject.parseObject(body);
        String notifyUrl = msgObject.getString("notifyUrl");
        String orderSn = msgObject.getString("orderSn");
        if (StringUtils.isEmpty(notifyUrl)) {
            throw new Exception("获取mobile未支付订单超时取消api失败，notifyUrl为空");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> multiValueMap = new LinkedMultiValueMap<>();
        multiValueMap.add("orderSn", orderSn);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(multiValueMap, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(notifyUrl, request, String.class);
        log.info("unpaidOrder response:{}", response.getBody());
        if (response.getStatusCode().value() != HttpStatus.OK.value()) {
            throw new Exception("调用mobile未支付订单超时取消api失败， body：" + body);
        }
        JSONObject jsonObject = JSONObject.parseObject(response.getBody());
        if (jsonObject != null && MQConstants.RESULT_SUCCESS_CODE != jsonObject.getInteger("code")) {
            throw new Exception("调用mobile未支付订单超时取消api失败， resp：" + jsonObject);
        }
    }

    /**
     * 调用 mobile 秒杀未支付关单回调。
     *
     * @param body MQ 消息体
     * @throws Exception 回调失败
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 2000L, multiplier = 1.5))
    @Override
    public void unpaidSeckillOrder(String body) throws Exception {
        JSONObject msgObject = JSONObject.parseObject(body);
        String notifyUrl = msgObject.getString("notifyUrl");
        String orderSn = msgObject.getString("orderSn");
        if (StringUtils.isEmpty(notifyUrl)) {
            throw new Exception("获取mobile秒杀未支付订单超时取消api失败，notifyUrl为空");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> multiValueMap = new LinkedMultiValueMap<>();
        multiValueMap.add("orderSn", orderSn);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(multiValueMap, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(notifyUrl, request, String.class);
        log.info("unpaidSeckillOrder response:{}", response.getBody());
        validateResponse(response, body, "调用mobile秒杀未支付订单超时取消api失败");
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 2000L, multiplier = 1.5))
    @Override
    public void sendEmail(String body) throws Exception {
        JSONObject msgObject = JSONObject.parseObject(body);
        String notifyUrl = msgObject.getString("notifyUrl");
        if (StringUtils.isEmpty(notifyUrl)) {
            throw new Exception("获取mobile发送邮件api失败，notifyUrl为空");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> multiValueMap = new LinkedMultiValueMap<>();
        multiValueMap.add("subject", msgObject.get("subject"));
        multiValueMap.add("content", msgObject.get("content"));
        multiValueMap.add("tos", msgObject.get("tos"));
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(multiValueMap, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(notifyUrl, request, String.class);
        log.info("sendEmail response:{}", response.getBody());
        if (response.getStatusCode().value() != HttpStatus.OK.value()) {
            throw new Exception("调用mobile发送邮件api失败， body：" + body);
        }
        JSONObject jsonObject = JSONObject.parseObject(response.getBody());
        if (jsonObject != null && MQConstants.RESULT_SUCCESS_CODE != jsonObject.getInteger("code")) {
            throw new Exception("调用mobile发送邮件api失败， resp：" + jsonObject);
        }
    }

    /**
     * 校验 mobile 回调响应。
     *
     * @param response 回调响应
     * @param body 原始消息体
     * @param errorPrefix 错误前缀
     * @throws Exception 响应失败
     */
    private void validateResponse(ResponseEntity<String> response, String body, String errorPrefix) throws Exception {
        if (response.getStatusCode().value() != HttpStatus.OK.value()) {
            throw new Exception(errorPrefix + "， body：" + body);
        }
        JSONObject jsonObject = JSONObject.parseObject(response.getBody());
        if (jsonObject != null && MQConstants.RESULT_SUCCESS_CODE != jsonObject.getInteger("code")) {
            throw new Exception(errorPrefix + "， resp：" + jsonObject);
        }
    }
}
