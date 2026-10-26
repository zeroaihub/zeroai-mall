package com.zeroai.simulator.engine;

import com.zeroai.simulator.auth.AccountPool;
import com.zeroai.simulator.auth.VirtualUser;
import com.zeroai.simulator.config.SimConfig;
import com.zeroai.simulator.metric.Metrics;
import com.zeroai.simulator.scenario.Scenario;
import com.zeroai.simulator.util.RateLimiter;
import com.zeroai.simulator.util.ThinkTime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 压测引擎。
 * <p>
 * 并发模型刻意做成三个解耦的维度，避免常见误区：
 * <ul>
 *   <li><b>账号数</b>：有多少个不同身份在操作（决定真实感与数据分布）；</li>
 *   <li><b>工作线程数</b>：同时在途的请求上限（决定能不能跟上目标 QPS）；</li>
 *   <li><b>λ 到达率</b>：QPS 的硬约束，由令牌桶控制，与上面两者无关。</li>
 * </ul>
 * 因此「1000 个用户并发」不等于「开 1000 个线程」：线程只负责把 λ 打满，
 * 账号池负责让 1000 个身份都被用到。这样调 QPS 不需要重算线程数。
 */
public final class Engine {

    private final SimConfig cfg;
    private final Scenario scenario;
    private final AccountPool accountPool;
    private final Metrics metrics;
    private final RateLimiter limiter;

    private final List<StageSnapshot> snapshots = new ArrayList<>();
    private volatile boolean running = true;

    public Engine(SimConfig cfg, Scenario scenario, AccountPool accountPool, Metrics metrics, RateLimiter limiter) {
        this.cfg = cfg;
        this.scenario = scenario;
        this.accountPool = accountPool;
        this.metrics = metrics;
        this.limiter = limiter;
    }

    public List<StageSnapshot> snapshots() {
        return snapshots;
    }

    /** 主动停止（供 Ctrl-C 钩子调用）。 */
    public void stop() {
        running = false;
        if (limiter != null) {
            limiter.close();
        }
    }

    /**
     * 执行压测。
     *
     * @throws InterruptedException 被中断
     */
    public void run() throws InterruptedException {
        if (isBurst()) {
            runBurst();
        } else {
            runOpenLoop();
        }
    }

    private boolean isBurst() {
        return "burst".equalsIgnoreCase(cfg.mode());
    }

    // ---------------------------------------------------------------- 开环阶梯

    private void runOpenLoop() throws InterruptedException {
        int workers = cfg.workers();
        ExecutorService workerPool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "vu-worker");
            t.setDaemon(true);
            return t;
        });
        metrics.start();
        for (int i = 0; i < workers; i++) {
            workerPool.submit(this::workerLoop);
        }

        System.out.printf("  工作线程 %d，账号 %d，开始阶梯加压%n", workers, accountPool.size());
        int index = 0;
        for (SimConfig.Stage stage : cfg.stages()) {
            if (!running) {
                break;
            }
            // 多进程时 λ 必须按进程数分摊，否则 N 个进程各打满目标值，实际 QPS 是 N 倍
            limiter.setLambda((double) stage.targetQps() / cfg.processCount());
            metrics.targetQps(stage.targetQps());

            long reqBefore = metrics.totalRequests();
            long iterBefore = metrics.iterations();
            long iterOkBefore = metrics.iterationsOk();
            long begin = System.currentTimeMillis();

            System.out.printf("%n  [阶段 %d] 目标 %d QPS，持续 %d 秒%n", index + 1, stage.targetQps(), stage.durationSec());
            sleepWithProgress(stage.durationSec() * 1000L, reqBefore, begin);

            long elapsedSec = Math.max(1, (System.currentTimeMillis() - begin) / 1000);
            long reqDelta = metrics.totalRequests() - reqBefore;
            snapshots.add(new StageSnapshot(
                    index + 1,
                    stage.targetQps(),
                    elapsedSec,
                    reqDelta,
                    (double) reqDelta / elapsedSec,
                    metrics.iterations() - iterBefore,
                    metrics.iterationsOk() - iterOkBefore));
            index++;
        }

        System.out.println();
        shutdownWorkers(workerPool);
    }

    /**
     * 分段睡眠并打印本阶段实时 QPS。
     * <p>
     * 打印的是「当前阶段」的到达率，不是全局平均值：全局平均值在升档时会严重滞后，
     * 看起来像「加了压但 QPS 没上去」，容易误判成系统到顶。
     */
    private void sleepWithProgress(long totalMs, long stageReqBefore, long stageBeginMs) throws InterruptedException {
        long end = System.currentTimeMillis() + totalMs;
        long interval = Math.max(1000, cfg.reportIntervalSec() * 1000);
        while (System.currentTimeMillis() < end && running) {
            long chunk = Math.min(interval, end - System.currentTimeMillis());
            if (chunk <= 0) {
                break;
            }
            Thread.sleep(chunk);
            long stageElapsed = Math.max(1, (System.currentTimeMillis() - stageBeginMs) / 1000);
            long stageReq = metrics.totalRequests() - stageReqBefore;
            System.out.printf("    %3ds | 本阶段 %.1f QPS | 链路成功 %.1f%% | 本阶段请求 %,d%n",
                    metrics.elapsedSeconds(),
                    (double) stageReq / stageElapsed,
                    metrics.iterationSuccessRate() * 100,
                    stageReq);
        }
    }

    /**
     * 优雅停机。
     * <p>
     * 不用 shutdownNow 直接打断：正在途中的请求被打断后会以 INTERRUPTED 计入失败，
     * 让最后一段成功率凭空下跌，看起来像系统在收尾阶段崩了。
     */
    private void shutdownWorkers(ExecutorService workerPool) throws InterruptedException {
        running = false;
        if (limiter != null) {
            limiter.close();
        }
        workerPool.shutdown();
        // 给在途请求和最后一轮思考时间留出余量
        if (!workerPool.awaitTermination(cfg.thinkMaxMs() + 3000, TimeUnit.MILLISECONDS)) {
            workerPool.shutdownNow();
        }
    }

    private void workerLoop() {
        while (running) {
            // 必须租用「当前空闲」的用户：同一账号同时跑两轮会出现
            // 「A 线程刚加购、B 线程下单把购物车消费掉」这种自造故障，压出来的失败全是噪声。
            VirtualUser user = accountPool.lease();
            if (user == null) {
                // 所有账号都在跑，说明并发已经达到「真实用户数」上限，退让一下再来
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            boolean ok;
            try {
                ok = scenario.run(user);
            } catch (Throwable t) {
                // 场景层抛异常说明是压测端或契约问题，单独埋点，不让它静默终止工作线程
                metrics.record("chain.error", 0, t.getClass().getSimpleName());
                ok = false;
            } finally {
                accountPool.release(user);
            }
            metrics.markIteration(ok);
            if (!running) {
                break;
            }
            ThinkTime.sleep(cfg.thinkMinMs(), cfg.thinkMaxMs(), cfg.thinkMedianMs());
        }
    }

    // ---------------------------------------------------------------- 秒杀齐射

    private void runBurst() throws InterruptedException {
        int burstUsers = Math.min(cfg.seckillBurstUsers(), accountPool.size());
        List<VirtualUser> users = accountPool.all().subList(0, burstUsers);
        System.out.printf("  齐射模式：%d 个用户，先预签 token%n", burstUsers);
        scenario.prepare(users);

        long at = cfg.burstAtEpochMs();
        if (at > 0) {
            long waitMs = at - System.currentTimeMillis();
            if (waitMs > 0) {
                System.out.printf("  距齐射时刻还有 %d ms，等待中%n", waitMs);
                Thread.sleep(waitMs);
            }
        }

        metrics.start();
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(burstUsers, cfg.workers()), r -> {
            Thread t = new Thread(r, "burst-worker");
            t.setDaemon(true);
            return t;
        });
        for (VirtualUser user : users) {
            pool.submit(() -> {
                try {
                    gate.await();
                    boolean ok;
                    try {
                        ok = scenario.run(user);
                    } catch (Throwable t) {
                        metrics.record("chain.error", 0, t.getClass().getSimpleName());
                        ok = false;
                    }
                    metrics.markIteration(ok);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.incrementAndGet();
                }
            });
        }
        // 所有线程就位后同时放行，保证是「齐射」而不是「排队起跑」
        gate.countDown();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.MINUTES);

        long reqDelta = metrics.totalRequests();
        snapshots.add(new StageSnapshot(1, burstUsers, metrics.elapsedSeconds(), reqDelta,
                (double) reqDelta / metrics.elapsedSeconds(), metrics.iterations(), metrics.iterationsOk()));
    }

    /**
     * 一档负载的运行结果。
     *
     * @param stageIndex   阶段序号
     * @param targetQps    目标到达率
     * @param durationSec  实际持续秒数
     * @param requests     本阶段请求数
     * @param actualQps    本阶段实测到达率
     * @param iterations   本阶段链路轮数
     * @param iterationsOk 本阶段链路成功轮数
     */
    public record StageSnapshot(int stageIndex, int targetQps, long durationSec, long requests,
                                double actualQps, long iterations, long iterationsOk) {

        public double chainSuccessRate() {
            return iterations == 0 ? 0 : (double) iterationsOk / iterations;
        }
    }
}
