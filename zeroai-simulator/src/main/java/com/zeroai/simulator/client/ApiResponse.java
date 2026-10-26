package com.zeroai.simulator.client;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * 一次接口调用的结果。
 * <p>
 * 同时保留业务码、HTTP 状态和原始报文：压测出问题时，业务码能告诉我们是哪条校验拦下了请求，
 * 而这些信息在只看成功率的报表里会全部丢失。
 */
public final class ApiResponse {

    private final String step;
    private final int httpStatus;
    private final int code;
    private final String msg;
    private final Object data;
    private final String raw;
    private final long costMs;
    private final String transportError;

    private ApiResponse(String step, int httpStatus, int code, String msg, Object data,
                        String raw, long costMs, String transportError) {
        this.step = step;
        this.httpStatus = httpStatus;
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.raw = raw;
        this.costMs = costMs;
        this.transportError = transportError;
    }

    public static ApiResponse of(String step, int httpStatus, int code, String msg,
                                 Object data, String raw, long costMs) {
        return new ApiResponse(step, httpStatus, code, msg, data, raw, costMs, null);
    }

    public static ApiResponse failed(String step, long costMs, String transportError) {
        return new ApiResponse(step, -1, -1, null, null, null, costMs, transportError);
    }

    public String step() {
        return step;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public int code() {
        return code;
    }

    public String msg() {
        return msg;
    }

    public Object data() {
        return data;
    }

    public String raw() {
        return raw;
    }

    public long costMs() {
        return costMs;
    }

    public String transportError() {
        return transportError;
    }

    public boolean ok() {
        return transportError == null && code == 200;
    }

    /** data 作为对象取，不是对象时返回 null。 */
    public JSONObject dataObject() {
        return data instanceof JSONObject o ? o : null;
    }

    /** data 作为数组取，不是数组时返回 null。 */
    public JSONArray dataArray() {
        return data instanceof JSONArray a ? a : null;
    }

    /**
     * 失败标签，供指标分桶使用。
     *
     * @return 成功时返回 null
     */
    public String errorTag() {
        if (transportError != null) {
            return transportError;
        }
        if (httpStatus != 200) {
            return "HTTP_" + httpStatus;
        }
        if (code != 200) {
            return "BIZ_" + code;
        }
        return null;
    }

    @Override
    public String toString() {
        return "ApiResponse{" + step + ", http=" + httpStatus + ", code=" + code
                + ", msg=" + msg + ", cost=" + costMs + "ms}";
    }
}
