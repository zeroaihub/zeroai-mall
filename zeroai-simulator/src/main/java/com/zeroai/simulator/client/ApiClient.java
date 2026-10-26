package com.zeroai.simulator.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zeroai.simulator.config.SimConfig;
import com.zeroai.simulator.metric.Metrics;
import com.zeroai.simulator.util.RateLimiter;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 统一 HTTP 客户端。
 * <p>
 * 所有请求都从这里出去，因此这里做三件事，业务场景代码就不用重复关心：
 * <ol>
 *   <li>取令牌桶许可（QPS 全局硬约束，与场景结构无关）；</li>
 *   <li>统一解析 {@code R<T>} 信封并按业务码生成失败标签；</li>
 *   <li>把耗时与结果写入指标中心。</li>
 * </ol>
 */
public final class ApiClient {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final Metrics metrics;
    private final RateLimiter limiter;
    private final String baseUrl;
    private final String adminBaseUrl;

    public ApiClient(SimConfig cfg, Metrics metrics, RateLimiter limiter) {
        this.metrics = metrics;
        this.limiter = limiter;
        this.baseUrl = cfg.baseUrl();
        this.adminBaseUrl = cfg.adminBaseUrl();

        Dispatcher dispatcher = new Dispatcher();
        // 同步调用不经过 Dispatcher 限流，这里仍放开上限，避免后续误用异步接口时被默认 64/5 卡住
        dispatcher.setMaxRequests(Integer.MAX_VALUE);
        dispatcher.setMaxRequestsPerHost(Integer.MAX_VALUE);

        this.http = new OkHttpClient.Builder()
                .connectTimeout(cfg.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(cfg.readTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(cfg.readTimeoutMs(), TimeUnit.MILLISECONDS)
                .callTimeout(cfg.readTimeoutMs() * 2, TimeUnit.MILLISECONDS)
                .connectionPool(new ConnectionPool(cfg.httpMaxIdle(), 30, TimeUnit.SECONDS))
                .dispatcher(dispatcher)
                .retryOnConnectionFailure(false)
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String adminBaseUrl() {
        return adminBaseUrl;
    }

    // ---------------------------------------------------------------- 对外方法

    /**
     * 移动端 GET。
     *
     * @param step  指标步骤名
     * @param path  相对路径，如 {@code /search/result}
     * @param query 查询参数，可为 null
     * @param token 登录态，可为 null
     * @return 调用结果
     */
    public ApiResponse get(String step, String path, Map<String, String> query, String token) {
        return execute(step, buildGet(baseUrl, path, query, token), null);
    }

    /**
     * 移动端 POST JSON。
     *
     * @param step  指标步骤名
     * @param path  相对路径
     * @param body  请求体对象，null 表示空对象
     * @param token 登录态
     * @return 调用结果
     */
    public ApiResponse post(String step, String path, Object body, String token) {
        return execute(step, buildPost(baseUrl, path, body, token), body);
    }

    /**
     * 管理端 GET。
     *
     * @param step  指标步骤名
     * @param path  相对路径
     * @param query 查询参数
     * @param token 后台登录态
     * @return 调用结果
     */
    public ApiResponse getAdmin(String step, String path, Map<String, String> query, String token) {
        return execute(step, buildGet(adminBaseUrl, path, query, token), null);
    }

    /**
     * 管理端 POST JSON。
     *
     * @param step  指标步骤名
     * @param path  相对路径
     * @param body  请求体对象
     * @param token 后台登录态
     * @return 调用结果
     */
    public ApiResponse postAdmin(String step, String path, Object body, String token) {
        return execute(step, buildPost(adminBaseUrl, path, body, token), body);
    }

    /**
     * 移动端 DELETE。
     *
     * @param step  指标步骤名
     * @param path  相对路径
     * @param token 登录态
     * @return 调用结果
     */
    public ApiResponse delete(String step, String path, String token) {
        HttpUrl parsed = HttpUrl.parse(baseUrl + path);
        if (parsed == null) {
            throw new IllegalArgumentException("非法 URL: " + baseUrl + path);
        }
        Request.Builder rb = new Request.Builder().url(parsed).delete();
        applyAuth(rb, token);
        return execute(step, rb.build(), null);
    }

    /**
     * 移动端 GET，但不写指标。
     * <p>
     * 专门给「轮询等待异步结果」用：轮询本身就是重试语义，前几次「还没好」是预期内的，
     * 如果每次尝试都记一次失败，报表上会看到 searchResult 只有 30% 成功率，
     * 而实际链路是通的——这种噪声会让人误判系统有问题，也会掩盖真正的错误。
     * 调用方负责在轮询结束时按「总耗时 + 最终结果」记一条。
     *
     * @param step  标签名（仅用于日志，不进指标）
     * @param path  相对路径
     * @param query 查询参数
     * @param token 登录态
     * @return 调用结果
     */
    public ApiResponse getQuiet(String step, String path, Map<String, String> query, String token) {
        return execute(step, buildGet(baseUrl, path, query, token), null, false);
    }

    // ---------------------------------------------------------------- 内部实现

    private Request buildGet(String root, String path, Map<String, String> query, String token) {
        HttpUrl parsed = HttpUrl.parse(root + path);
        if (parsed == null) {
            throw new IllegalArgumentException("非法 URL: " + root + path);
        }
        HttpUrl.Builder urlBuilder = parsed.newBuilder();
        if (query != null) {
            query.forEach(urlBuilder::addQueryParameter);
        }
        Request.Builder rb = new Request.Builder().url(urlBuilder.build()).get();
        applyAuth(rb, token);
        return rb.build();
    }

    private Request buildPost(String root, String path, Object body, String token) {
        String json = body == null ? "{}" : JSON.toJSONString(body);
        RequestBody requestBody = RequestBody.create(json, JSON_TYPE);
        Request.Builder rb = new Request.Builder().url(root + path).post(requestBody);
        applyAuth(rb, token);
        return rb.build();
    }

    private void applyAuth(Request.Builder rb, String token) {
        // 移动端既支持裸 token 也支持 Bearer，统一用裸 token，少一层字符串处理
        if (token != null && !token.isEmpty()) {
            rb.header("Authorization", token);
        }
    }

    private ApiResponse execute(String step, Request request, Object body) {
        return execute(step, request, body, true);
    }

    private ApiResponse execute(String step, Request request, Object body, boolean measure) {
        // 先取许可再开始计时：排队等许可的时间是压测端的调度等待，
        // 混进 RT 会让「限流越狠、延迟越高」的假象出现在报表里，P99 完全失真。
        if (limiter != null) {
            try {
                limiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 停机时的中断不是一次真实请求，不写入指标，避免污染成功率
                return ApiResponse.failed(step, 0, "INTERRUPTED");
            }
        }
        long begin = System.nanoTime();
        try {
            try (Response response = http.newCall(request).execute()) {
                long cost = (System.nanoTime() - begin) / 1_000_000;
                int status = response.code();
                ResponseBody rb = response.body();
                String text = rb == null ? "" : rb.string();
                ApiResponse result = parse(step, status, text, cost);
                if (measure) {
                    metrics.record(step, cost, result.errorTag());
                }
                return result;
            }
        } catch (SocketTimeoutException e) {
            long cost = (System.nanoTime() - begin) / 1_000_000;
            if (measure) {
                metrics.record(step, cost, "NET_TIMEOUT");
            }
            return ApiResponse.failed(step, cost, "NET_TIMEOUT");
        } catch (IOException e) {
            long cost = (System.nanoTime() - begin) / 1_000_000;
            if (measure) {
                metrics.record(step, cost, "NET_ERROR");
            }
            return ApiResponse.failed(step, cost, "NET_ERROR");
        }
    }

    private ApiResponse parse(String step, int status, String text, long cost) {
        if (text == null || text.isEmpty()) {
            return ApiResponse.of(step, status, status == 200 ? -2 : status, "empty_body", null, text, cost);
        }
        try {
            JSONObject obj = JSON.parseObject(text);
            if (obj == null) {
                return ApiResponse.of(step, status, -2, "unparsable", null, text, cost);
            }
            int code = obj.getIntValue("code", -2);
            return ApiResponse.of(step, status, code, obj.getString("msg"), obj.get("data"), text, cost);
        } catch (Exception e) {
            // 非 JSON 响应（例如网关错误页、Spring 默认错误页）按不可解析处理
            return ApiResponse.of(step, status, -2, "not_json", null, text, cost);
        }
    }

    public void close() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
