package com.zeroai.simulator.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 思考时间采样。
 * <p>
 * 用对数正态分布而不是固定 sleep：固定值会让整批用户同时发请求，
 * 打出「脉冲式」流量，既不像真人，也会让分位延迟失真。
 * 对数正态的中位数可配，长尾自然产生间隔较大的用户。
 */
public final class ThinkTime {

    /** 对数正态的离散度，0.6 时约 68% 的采样落在中位数的 0.55~1.8 倍区间。 */
    private static final double SIGMA = 0.6;

    private ThinkTime() {
    }

    /**
     * 按分布睡眠一段思考时间。
     *
     * @param minMs    下限
     * @param maxMs    上限
     * @param medianMs 中位数
     */
    public static void sleep(long minMs, long maxMs, long medianMs) {
        long ms = sample(minMs, maxMs, medianMs);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 采样一个思考时间。
     *
     * @param minMs    下限
     * @param maxMs    上限
     * @param medianMs 中位数
     * @return 毫秒
     */
    public static long sample(long minMs, long maxMs, long medianMs) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double u1 = Math.max(r.nextDouble(), 1e-9);
        double u2 = r.nextDouble();
        double gauss = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
        double value = medianMs * Math.exp(SIGMA * gauss);
        long result = Math.round(value);
        return Math.min(maxMs, Math.max(minMs, result));
    }
}
