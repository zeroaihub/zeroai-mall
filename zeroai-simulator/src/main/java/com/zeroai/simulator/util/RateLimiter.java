package com.zeroai.simulator.util;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 开环令牌桶。
 * <p>
 * 到达率由 tick 时发放的许可数决定，与工作线程数解耦：线程多只是更容易跟上 λ，
 * 线程少则实际 QPS 低于 λ（这时报告里「实测 QPS < 目标 QPS」就是明确的扩容信号）。
 * <p>
 * 用有界队列而不是无界队列：无界队列会把「系统变慢」伪装成内存增长，
 * 有界队列在满载时会阻塞调用方，从而诚实地反映背压。
 */
public final class RateLimiter {

    private final BlockingQueue<Object> permits = new ArrayBlockingQueue<>(200_000);
    private final ScheduledExecutorService ticker;
    private final long tickMs;
    private final AtomicLong issued = new AtomicLong();
    private final AtomicLong totalWaitMs = new AtomicLong();
    private final AtomicLong waitCount = new AtomicLong();
    private final AtomicLong lagEvents = new AtomicLong();

    private volatile double lambda;
    private double carry;
    private volatile boolean running = true;

    /**
     * 创建并启动令牌桶。
     *
     * @param lambda 每秒许可数
     * @param tickMs tick 间隔毫秒
     */
    public RateLimiter(double lambda, long tickMs) {
        this.lambda = lambda;
        this.tickMs = Math.max(1, tickMs);
        this.ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-ticker");
            t.setDaemon(true);
            return t;
        });
        this.ticker.scheduleAtFixedRate(this::tick, 0, this.tickMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 动态调整目标速率，用于阶梯加压。
     *
     * @param newLambda 新的每秒许可数
     */
    public void setLambda(double newLambda) {
        this.lambda = Math.max(0, newLambda);
    }

    public double lambda() {
        return lambda;
    }

    private void tick() {
        if (!running) {
            return;
        }
        double want = lambda * tickMs / 1000.0 + carry;
        int n = (int) Math.floor(want);
        carry = want - n;
        for (int i = 0; i < n; i++) {
            if (!permits.offer(Boolean.TRUE)) {
                // 队列满说明消费端严重滞后，丢弃许可并计数，避免积压后瞬间喷发
                lagEvents.incrementAndGet();
                break;
            }
            issued.incrementAndGet();
        }
    }

    /**
     * 取一个许可，必要时阻塞。
     * <p>
     * 阻塞是刻意的：QPS 目标是硬约束，宁可让工作线程等，也不能突破 λ。
     *
     * @throws InterruptedException 线程被中断
     */
    public void acquire() throws InterruptedException {
        long begin = System.nanoTime();
        while (running) {
            if (permits.poll(200, TimeUnit.MILLISECONDS) != null) {
                totalWaitMs.addAndGet((System.nanoTime() - begin) / 1_000_000);
                waitCount.incrementAndGet();
                return;
            }
        }
    }

    public long issued() {
        return issued.get();
    }

    /**
     * 平均调度等待。
     * <p>
     * 用均值而不是峰值：峰值会被停机、GC、调度抖动放大成几十秒的离群值，
     * 反而看不出「工作线程是否够用」这个真正想回答的问题。
     *
     * @return 平均等待毫秒
     */
    public long meanWaitMs() {
        long c = waitCount.get();
        return c == 0 ? 0 : totalWaitMs.get() / c;
    }

    public long lagEvents() {
        return lagEvents.get();
    }

    public void close() {
        running = false;
        ticker.shutdownNow();
    }
}
