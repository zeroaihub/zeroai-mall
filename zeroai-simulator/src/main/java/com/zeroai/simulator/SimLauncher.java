package com.zeroai.simulator;

import com.zeroai.simulator.auth.AccountPool;
import com.zeroai.simulator.auth.TokenWarmup;
import com.zeroai.simulator.client.AdminApi;
import com.zeroai.simulator.client.ApiClient;
import com.zeroai.simulator.client.ApiResponse;
import com.zeroai.simulator.client.MobileApi;
import com.zeroai.simulator.config.SimConfig;
import com.zeroai.simulator.engine.Engine;
import com.zeroai.simulator.metric.Metrics;
import com.zeroai.simulator.metric.ReportWriter;
import com.zeroai.simulator.scenario.PurchaseScenario;
import com.zeroai.simulator.scenario.Scenario;
import com.zeroai.simulator.scenario.SearchOnlyScenario;
import com.zeroai.simulator.scenario.SeckillScenario;
import com.zeroai.simulator.util.RateLimiter;
import com.zeroai.simulator.verify.Reconcile;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令行入口。
 * <p>
 * 子命令：
 * <pre>
 *   warmup   预创建账号并缓存 token（幂等，可重复执行补号）
 *   smoke    单用户跑一轮，验证链路与账号可用性
 *   baseline 记录压测前水位（库存 / 订单数）
 *   run      正式压测
 *   verify   压测后对账
 * </pre>
 * 把 warmup / smoke / baseline / run / verify 拆成独立子命令，是为了让每一步都能单独重跑：
 * 压测出问题时，可以只补账号、只重跑对账，而不用从头再来一遍。
 */
public final class SimLauncher {

    private SimLauncher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        String command = args[0];
        String configPath = "conf/sim-local.yml";
        int warmupSize = -1;
        boolean confirmed = false;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-c", "--config" -> configPath = args[++i];
                case "--size" -> warmupSize = Integer.parseInt(args[++i]);
                case "--yes", "-y" -> confirmed = true;
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    usage();
                    return;
                }
            }
        }

        SimConfig cfg = SimConfig.load(Path.of(configPath));
        System.out.println("配置: " + Path.of(configPath).toAbsolutePath());
        System.out.println("目标: " + cfg.baseUrl() + "（admin: " + cfg.adminBaseUrl() + "）");
        System.out.println("场景: " + cfg.scenario() + "，账号: " + cfg.accountSize()
                + "，线程: " + cfg.workers() + "，分片: " + cfg.processIndex() + "/" + cfg.processCount());

        switch (command) {
            case "warmup" -> doWarmup(cfg, warmupSize > 0 ? warmupSize : cfg.accountSize());
            case "smoke" -> doSmoke(cfg);
            case "baseline" -> new Reconcile(cfg).baseline();
            case "run" -> doRun(cfg, confirmed);
            case "verify" -> {
                boolean ok = new Reconcile(cfg).verify();
                if (!ok) {
                    System.exit(1);
                }
            }
            default -> usage();
        }
    }

    private static void usage() {
        System.out.println("""
                用法: java -jar zeroai-simulator.jar <命令> [-c 配置文件] [参数]

                命令:
                  warmup    预创建账号并缓存 token，幂等
                            --size N   目标账号数，默认取配置 sim.account.size
                  smoke     单用户跑一轮完整链路，验证环境与账号
                  baseline  记录压测前水位，供 verify 对账
                  run       正式压测（必须显式加 --yes 才会开始）
                  verify    压测后对账，失败时退出码为 1

                示例:
                  java -jar zeroai-simulator.jar warmup -c conf/sim-local.yml --size 500
                  java -jar zeroai-simulator.jar smoke  -c conf/sim-local.yml
                  java -jar zeroai-simulator.jar run    -c conf/sim-local.yml --yes
                """);
    }

    // ---------------------------------------------------------------- warmup

    private static void doWarmup(SimConfig cfg, int size) throws Exception {
        AccountPool accountPool = new AccountPool(Path.of(cfg.accountPoolFile()));
        accountPool.load();
        System.out.printf("账号池已有 %d 个，目标 %d 个%n", accountPool.size(), size);

        ApiClient http = new ApiClient(cfg, new Metrics(), null);
        MobileApi mobile = new MobileApi(http);
        try {
            long begin = System.currentTimeMillis();
            int created = new TokenWarmup(cfg, mobile, accountPool).warmup(size);
            long cost = (System.currentTimeMillis() - begin) / 1000;
            System.out.printf("预热完成：新建 %d，账号池共 %d，耗时 %d 秒%n",
                    created, accountPool.size(), cost);
            System.out.println("账号文件: " + Path.of(cfg.accountPoolFile()).toAbsolutePath());
        } finally {
            http.close();
        }
    }

    // ---------------------------------------------------------------- smoke

    private static void doSmoke(SimConfig cfg) throws Exception {
        AccountPool accountPool = new AccountPool(Path.of(cfg.accountPoolFile()));
        accountPool.load();
        if (accountPool.size() == 0) {
            System.err.println("账号池为空，请先执行 warmup");
            return;
        }
        Metrics metrics = new Metrics();
        ApiClient http = new ApiClient(cfg, metrics, null);
        try {
            MobileApi mobile = new MobileApi(http);
            AdminApi admin = new AdminApi(http);
            Scenario scenario = buildScenario(cfg, mobile, admin, metrics);
            metrics.start();
            boolean ok = scenario.run(accountPool.first());
            metrics.markIteration(ok);
            ReportWriter.printConsole(cfg, metrics, new ArrayList<>(), null);
            System.out.println(ok ? "SMOKE 通过：链路正常" : "SMOKE 未走通：看上面失败分布定位卡在哪一步");
        } finally {
            http.close();
        }
    }

    // ---------------------------------------------------------------- run

    private static void doRun(SimConfig cfg, boolean confirmed) throws Exception {
        guardTarget(cfg.baseUrl());
        if (!confirmed) {
            System.err.println("""
                    已拒绝启动：正式压测必须显式确认。
                    确认目标环境无误后，追加 --yes 重跑。
                    """);
            return;
        }
        AccountPool accountPool = new AccountPool(Path.of(cfg.accountPoolFile()));
        accountPool.load();
        if (accountPool.size() == 0) {
            System.err.println("账号池为空，请先执行 warmup");
            return;
        }

        Metrics metrics = new Metrics();
        boolean burst = "burst".equalsIgnoreCase(cfg.mode());
        RateLimiter limiter = burst ? null : new RateLimiter(0, cfg.tickMs());

        ApiClient http = new ApiClient(cfg, metrics, limiter);
        try {
            MobileApi mobile = new MobileApi(http);
            AdminApi admin = new AdminApi(http);
            Scenario scenario = buildScenario(cfg, mobile, admin, metrics);
            Engine engine = new Engine(cfg, scenario, accountPool, metrics, limiter);
            Runtime.getRuntime().addShutdownHook(new Thread(engine::stop));

            printRunHeader(cfg, accountPool.size(), burst);
            engine.run();

            ReportWriter.printConsole(cfg, metrics, engine.snapshots(), limiter);
            Path dir = ReportWriter.write(cfg, metrics, engine.snapshots(), limiter);
            System.out.println("报告: " + dir.toAbsolutePath());
            if (!burst) {
                System.out.printf("%n对账提示：未支付订单还有 %d 秒超时关单窗口，建议 %d 秒后再执行 verify%n",
                        Reconcile.UNPAID_CANCEL_WINDOW_SEC, Reconcile.UNPAID_CANCEL_WINDOW_SEC + 30);
            }
        } finally {
            if (limiter != null) {
                limiter.close();
            }
            http.close();
        }
    }

    private static void printRunHeader(SimConfig cfg, int accounts, boolean burst) {
        System.out.println();
        System.out.println("================ 压测开始 ================");
        System.out.println("并发用户(账号) : " + accounts);
        System.out.println("在途上限(线程) : " + cfg.workers());
        if (burst) {
            System.out.println("模式           : burst 齐射，用户数 " + cfg.seckillBurstUsers());
        } else {
            System.out.println("模式           : open_loop 开环，阶梯 " + cfg.stages());
        }
        System.out.println("思考时间       : " + cfg.thinkMinMs() + "~" + cfg.thinkMaxMs()
                + " ms（中位 " + cfg.thinkMedianMs() + " ms）");
        System.out.println("支付方式       : payType=99 模拟支付（不触达真实渠道）");
        System.out.println("==========================================");
    }

    // ---------------------------------------------------------------- 组装

    private static Scenario buildScenario(SimConfig cfg, MobileApi mobile, AdminApi admin, Metrics metrics) {
        String name = cfg.scenario();
        return switch (name) {
            case "normal_purchase" -> new PurchaseScenario(cfg, mobile, admin, metrics);
            case "seckill" -> new SeckillScenario(cfg, mobile, admin, metrics);
            case "search_only" -> new SearchOnlyScenario(cfg, mobile, admin, metrics);
            default -> throw new IllegalArgumentException("未知场景: " + name
                    + "，可选 search_only / normal_purchase / seckill");
        };
    }

    /**
     * 目标环境保护。
     * <p>
     * 压测端最容易造成的伤害是「打错环境」。这里只对非内网地址做硬拦截，
     * 内网/本机放行以免影响正常自测。
     *
     * @param baseUrl 目标地址
     */
    private static void guardTarget(String baseUrl) {
        String host;
        try {
            host = URI.create(baseUrl).getHost();
        } catch (Exception e) {
            throw new IllegalStateException("非法的目标地址: " + baseUrl, e);
        }
        if (host == null) {
            throw new IllegalStateException("目标地址缺少 host: " + baseUrl);
        }
        List<String> allowedPrefix = List.of("127.", "192.168.", "10.", "172.16.", "172.17.", "172.18.");
        boolean local = "localhost".equals(host) || host.endsWith(".local");
        boolean intranet = allowedPrefix.stream().anyMatch(host::startsWith);
        if (!local && !intranet) {
            throw new IllegalStateException("""
                    目标不是本机/内网地址，已拒绝启动：%s
                    压测端只允许打本地或内网环境。若确实要压外部环境，请先确认不是生产，再手动放宽此校验。"""
                    .formatted(host));
        }
    }
}
