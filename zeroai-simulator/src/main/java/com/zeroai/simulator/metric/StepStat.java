package com.zeroai.simulator.metric;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个步骤（一个接口或一段业务动作）的统计。
 * <p>
 * 成功/失败的分界不是 HTTP 状态码，而是业务码：只有 {@code code == 200} 才算成功，
 * 这样「服务返回 200 但业务失败」不会被算进成功率。
 */
public final class StepStat {

    private final String name;
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong ok = new AtomicLong();
    /** 业务码/网络错误分桶，key 形如 BIZ_5001、HTTP_502、NET_ERROR。 */
    private final Map<String, AtomicLong> errors = new ConcurrentHashMap<>();
    private final LatencyRecorder latency = new LatencyRecorder();

    public StepStat(String name) {
        this.name = name;
    }

    public void record(long costMs, String errorTag) {
        total.incrementAndGet();
        latency.record(costMs);
        if (errorTag == null) {
            ok.incrementAndGet();
        } else {
            errors.computeIfAbsent(errorTag, k -> new AtomicLong()).incrementAndGet();
        }
    }

    public String name() {
        return name;
    }

    public long total() {
        return total.get();
    }

    public long ok() {
        return ok.get();
    }

    public long fail() {
        return total.get() - ok.get();
    }

    public double successRate() {
        long t = total.get();
        return t == 0 ? 0 : (double) ok.get() / t;
    }

    public LatencyRecorder latency() {
        return latency;
    }

    public Map<String, Long> errorCounts() {
        Map<String, Long> result = new ConcurrentHashMap<>();
        errors.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    /** 错误码分布摘要，报告里一行展示。 */
    public String errorSummary() {
        Map<String, Long> counts = errorCounts();
        if (counts.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(e.getKey()).append('=').append(e.getValue());
                });
        return sb.toString();
    }
}
