package com.zeroai.simulator.metric;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 毫秒级延迟直方图。
 * <p>
 * 用 1ms 定宽分桶（0~20000ms）替代外部 HDR 依赖：压测端只关心 P50/P95/P99 与超时占比，
 * 1ms 精度足够，且线程安全实现只要一个 AtomicIntegerArray，避免为压测工具再引一个第三方库。
 */
public final class LatencyRecorder {

    private static final int MAX_BUCKET_MS = 20_000;

    private final AtomicIntegerArray buckets = new AtomicIntegerArray(MAX_BUCKET_MS + 2);
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong sumMs = new AtomicLong();
    private final AtomicLong maxMs = new AtomicLong();

    /**
     * 记录一次耗时。
     *
     * @param costMs 耗时毫秒，负数按 0 处理
     */
    public void record(long costMs) {
        long value = Math.max(0, costMs);
        int idx = (int) Math.min(value, MAX_BUCKET_MS + 1);
        buckets.incrementAndGet(idx);
        total.incrementAndGet();
        sumMs.addAndGet(value);
        maxMs.accumulateAndGet(value, Math::max);
    }

    public long count() {
        return total.get();
    }

    public long max() {
        return maxMs.get();
    }

    public double mean() {
        long c = total.get();
        return c == 0 ? 0 : (double) sumMs.get() / c;
    }

    /**
     * 取分位值。
     *
     * @param percentile 0~100，例如 99 表示 P99
     * @return 分位耗时毫秒
     */
    public long percentile(double percentile) {
        long c = total.get();
        if (c == 0) {
            return 0;
        }
        long target = (long) Math.ceil(c * percentile / 100.0);
        long seen = 0;
        for (int i = 0; i < buckets.length(); i++) {
            seen += buckets.get(i);
            if (seen >= target) {
                return i;
            }
        }
        return MAX_BUCKET_MS;
    }
}
