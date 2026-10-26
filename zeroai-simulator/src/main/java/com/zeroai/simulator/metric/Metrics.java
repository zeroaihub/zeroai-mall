package com.zeroai.simulator.metric;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 压测指标中心。
 * <p>
 * 三层口径刻意分开：
 * <ul>
 *   <li>接口级：每步的成功率与分位延迟，用于定位是哪个接口退化；</li>
 *   <li>链路级：一轮完整用户行为是否走通，用于反映真实用户流失；</li>
 *   <li>时间级：每秒实际到达量，用于和配置 λ 对比，校验调度是否达标。</li>
 * </ul>
 * 三者不合并，否则「单接口 99% × 8 个串行步骤」的真实流失会被平均值掩盖。
 */
public final class Metrics {

    /** 最多记录 2 小时的逐秒到达量，超出部分丢弃。 */
    private static final int MAX_SECONDS = 7200;

    private final Map<String, StepStat> steps = new ConcurrentHashMap<>();
    private final AtomicLong iterations = new AtomicLong();
    private final AtomicLong iterationsOk = new AtomicLong();
    private final AtomicLongArray perSecond = new AtomicLongArray(MAX_SECONDS);

    private volatile long startEpochMs;
    private volatile int targetQps;

    public void start() {
        this.startEpochMs = System.currentTimeMillis();
    }

    public long startEpochMs() {
        return startEpochMs;
    }

    public void targetQps(int qps) {
        this.targetQps = qps;
    }

    public int targetQps() {
        return targetQps;
    }

    /**
     * 记录一次请求结果。
     *
     * @param step     步骤名
     * @param costMs   耗时
     * @param errorTag 失败标签，null 表示成功
     */
    public void record(String step, long costMs, String errorTag) {
        steps.computeIfAbsent(step, StepStat::new).record(costMs, errorTag);
        if (startEpochMs > 0) {
            int idx = (int) ((System.currentTimeMillis() - startEpochMs) / 1000);
            if (idx >= 0 && idx < MAX_SECONDS) {
                perSecond.incrementAndGet(idx);
            }
        }
    }

    /**
     * 记录一轮完整用户行为。
     *
     * @param ok 是否走通
     */
    public void markIteration(boolean ok) {
        iterations.incrementAndGet();
        if (ok) {
            iterationsOk.incrementAndGet();
        }
    }

    public long iterations() {
        return iterations.get();
    }

    public long iterationsOk() {
        return iterationsOk.get();
    }

    public double iterationSuccessRate() {
        long t = iterations.get();
        return t == 0 ? 0 : (double) iterationsOk.get() / t;
    }

    public StepStat step(String name) {
        return steps.computeIfAbsent(name, StepStat::new);
    }

    public Collection<StepStat> steps() {
        List<StepStat> list = new ArrayList<>(steps.values());
        list.sort((a, b) -> Long.compare(b.total(), a.total()));
        return list;
    }

    /** 已运行秒数（至少 1，避免除零）。 */
    public long elapsedSeconds() {
        if (startEpochMs <= 0) {
            return 1;
        }
        return Math.max(1, (System.currentTimeMillis() - startEpochMs) / 1000);
    }

    /** 全链路平均到达率。 */
    public double achievedQps() {
        return (double) totalRequests() / elapsedSeconds();
    }

    public long totalRequests() {
        long sum = 0;
        for (StepStat s : steps.values()) {
            sum += s.total();
        }
        return sum;
    }

    /**
     * 逐秒到达量快照，用于判断流量是不是被打成了脉冲。
     *
     * @return 每秒请求数，下标即秒偏移
     */
    public long[] perSecondSnapshot() {
        int realSeconds = (int) Math.min(elapsedSeconds(), MAX_SECONDS);
        long[] out = new long[realSeconds];
        for (int i = 0; i < realSeconds; i++) {
            out[i] = perSecond.get(i);
        }
        return out;
    }
}
