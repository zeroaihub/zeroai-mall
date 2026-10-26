package com.zeroai.simulator.metric;

import com.zeroai.simulator.config.SimConfig;
import com.zeroai.simulator.engine.Engine;
import com.zeroai.simulator.util.RateLimiter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 报告输出。
 * <p>
 * 报告头刻意把「容易让结论失效的环境参数」全部打印出来：
 * 工作线程、账号数、目标 QPS、部署形态。缺了这些，同一份数据换个环境就无法比较，
 * 也就没法回答「这次压测能不能代表线上」。
 */
public final class ReportWriter {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ReportWriter() {
    }

    /**
     * 生成报告目录并写出 HTML + CSV。
     *
     * @param cfg     配置
     * @param metrics 指标
     * @param stages  阶梯结果
     * @param limiter 令牌桶，可为 null
     * @return 报告目录
     * @throws IOException 写入失败
     */
    public static Path write(SimConfig cfg, Metrics metrics, List<Engine.StageSnapshot> stages,
                             RateLimiter limiter) throws IOException {
        String stamp = LocalDateTime.now().format(TS);
        Path dir = Path.of(cfg.outDir(), "report-" + stamp);
        Files.createDirectories(dir);

        List<StepStat> steps = List.copyOf(metrics.steps());
        Files.writeString(dir.resolve("metrics.csv"), buildCsv(steps), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("summary.html"), buildHtml(cfg, metrics, stages, limiter, steps),
                StandardCharsets.UTF_8);
        return dir;
    }

    /**
     * 打印控制台摘要。
     */
    public static void printConsole(SimConfig cfg, Metrics metrics, List<Engine.StageSnapshot> stages,
                                    RateLimiter limiter) {
        System.out.println();
        System.out.println("================ 压测结果 ================");
        System.out.printf("场景            : %s%n", cfg.scenario());
        System.out.printf("目标地址        : %s%n", cfg.baseUrl());
        System.out.printf("配置账号/线程  : %d / %d%n", cfg.accountSize(), cfg.workers());
        System.out.printf("运行时长        : %d 秒%n", metrics.elapsedSeconds());
        System.out.printf("总请求          : %,d%n", metrics.totalRequests());
        System.out.printf("实测 QPS        : %.1f%n", metrics.achievedQps());
        System.out.printf("链路轮数        : %,d（成功 %,d，%.1f%%）%n",
                metrics.iterations(), metrics.iterationsOk(), metrics.iterationSuccessRate() * 100);
        if (limiter != null) {
            System.out.printf("平均调度等待    : %d ms（许可排队，不含在 RT 内）%n", limiter.meanWaitMs());
            if (limiter.lagEvents() > 0) {
                System.out.printf("令牌积压丢弃    : %,d（消费端严重滞后）%n", limiter.lagEvents());
            }
        }

        if (!stages.isEmpty()) {
            System.out.println();
            System.out.println("-- 阶梯明细 ------------------------------------------------------------");
            System.out.printf("%-6s %-10s %-10s %-12s %-12s%n", "阶段", "目标QPS", "实测QPS", "本阶段请求", "链路成功率");
            for (Engine.StageSnapshot s : stages) {
                System.out.printf("%-6d %-10d %-10.1f %-12d %-12.1f%%%n",
                        s.stageIndex(), s.targetQps(), s.actualQps(), s.requests(), s.chainSuccessRate() * 100);
            }
        }

        System.out.println();
        System.out.println("-- 接口明细 ------------------------------------------------------------");
        List<StepStat> steps = List.copyOf(metrics.steps());
        System.out.printf("%-22s %8s %8s %10s %8s %8s %8s%n",
                "步骤", "总数", "成功%", "P50", "P95", "P99", "MAX");
        for (StepStat s : steps) {
            System.out.printf("%-22s %8d %7.1f%% %8d %8d %8d %8d%n",
                    s.name(),
                    s.total(),
                    s.successRate() * 100,
                    s.latency().percentile(50),
                    s.latency().percentile(95),
                    s.latency().percentile(99),
                    s.latency().max());
            String err = s.errorSummary();
            if (!"-".equals(err)) {
                System.out.printf("    └ 失败分布: %s%n", err);
            }
        }
        System.out.println("==========================================");
    }

    private static String buildCsv(List<StepStat> steps) {
        StringBuilder sb = new StringBuilder();
        sb.append("step,total,ok,fail,success_rate,p50_ms,p95_ms,p99_ms,max_ms,mean_ms,errors\n");
        for (StepStat s : steps) {
            sb.append(s.name()).append(',')
                    .append(s.total()).append(',')
                    .append(s.ok()).append(',')
                    .append(s.fail()).append(',')
                    .append(String.format("%.4f", s.successRate())).append(',')
                    .append(s.latency().percentile(50)).append(',')
                    .append(s.latency().percentile(95)).append(',')
                    .append(s.latency().percentile(99)).append(',')
                    .append(s.latency().max()).append(',')
                    .append(String.format("%.2f", s.latency().mean())).append(',')
                    .append('"').append(s.errorSummary().replace("\"", "'")).append('"')
                    .append('\n');
        }
        return sb.toString();
    }

    private static String buildHtml(SimConfig cfg, Metrics metrics, List<Engine.StageSnapshot> stages,
                                    RateLimiter limiter, List<StepStat> steps) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<title>zeroai-simulator 压测报告</title><style>")
                .append("body{font-family:-apple-system,'PingFang SC',sans-serif;margin:32px;color:#1f2329;background:#fafafa}")
                .append("h1{font-size:20px}h2{font-size:15px;margin-top:28px;border-left:3px solid #3370ff;padding-left:8px}")
                .append("table{border-collapse:collapse;background:#fff;font-size:13px;box-shadow:0 1px 3px rgba(0,0,0,.06)}")
                .append("th,td{border:1px solid #e5e6eb;padding:6px 12px;text-align:right}")
                .append("th{background:#f2f3f5;font-weight:600}td:first-child,th:first-child{text-align:left}")
                .append(".kv td:first-child{color:#646a73;width:200px}.warn{color:#d83931}")
                .append("</style></head><body>");

        sb.append("<h1>zeroai-simulator 压测报告</h1>");
        sb.append("<table class=\"kv\">");
        kv(sb, "场景", cfg.scenario());
        kv(sb, "目标地址", cfg.baseUrl());
        kv(sb, "账号数 / 工作线程", cfg.accountSize() + " / " + cfg.workers());
        kv(sb, "运行时长", metrics.elapsedSeconds() + " 秒");
        kv(sb, "总请求", String.format("%,d", metrics.totalRequests()));
        kv(sb, "实测 QPS", String.format("%.1f", metrics.achievedQps()));
        kv(sb, "链路轮数 / 成功", String.format("%,d / %,d（%.1f%%）", metrics.iterations(), metrics.iterationsOk(),
                metrics.iterationSuccessRate() * 100));
        if (limiter != null) {
            kv(sb, "平均调度等待", limiter.meanWaitMs() + " ms（不含在 RT 内）");
            kv(sb, "令牌积压丢弃", String.valueOf(limiter.lagEvents()));
        }
        sb.append("</table>");

        sb.append("<p class=\"warn\">注意：本次支付使用 payType=99 模拟支付，绕过渠道验签、支付流水与回调后置动作，")
                .append("该步骤耗时只反映数据库写入压力，不能作为生产支付容量结论。</p>");

        if (!stages.isEmpty()) {
            sb.append("<h2>阶梯明细</h2><table><tr><th>阶段</th><th>目标 QPS</th><th>实测 QPS</th>")
                    .append("<th>本阶段请求</th><th>链路成功率</th></tr>");
            for (Engine.StageSnapshot s : stages) {
                sb.append("<tr><td>").append(s.stageIndex()).append("</td><td>").append(s.targetQps())
                        .append("</td><td>").append(String.format("%.1f", s.actualQps()))
                        .append("</td><td>").append(s.requests()).append("</td><td>")
                        .append(String.format("%.1f%%", s.chainSuccessRate() * 100)).append("</td></tr>");
            }
            sb.append("</table>");
        }

        sb.append("<h2>接口明细</h2><table><tr><th>步骤</th><th>总数</th><th>成功率</th><th>P50</th>")
                .append("<th>P95</th><th>P99</th><th>MAX</th><th>失败分布</th></tr>");
        for (StepStat s : steps) {
            sb.append("<tr><td>").append(s.name()).append("</td><td>").append(s.total())
                    .append("</td><td>").append(String.format("%.1f%%", s.successRate() * 100))
                    .append("</td><td>").append(s.latency().percentile(50))
                    .append("</td><td>").append(s.latency().percentile(95))
                    .append("</td><td>").append(s.latency().percentile(99))
                    .append("</td><td>").append(s.latency().max())
                    .append("</td><td style=\"text-align:left\">").append(s.errorSummary())
                    .append("</td></tr>");
        }
        sb.append("</table>");

        sb.append("<h2>结论前必须补全的环境信息</h2><table class=\"kv\">")
                .append("<tr><td>服务端 tomcat.max-threads</td><td>待填</td></tr>")
                .append("<tr><td>服务端 HikariCP maximumPoolSize</td><td>待填</td></tr>")
                .append("<tr><td>JVM 堆 / CPU 核数</td><td>待填</td></tr>")
                .append("<tr><td>是否覆盖未支付超时关单窗口（60s）</td><td>待填</td></tr>")
                .append("</table>");
        sb.append("<p>缺少以上参数时，本报告只能横向自比，不能对外宣称容量。</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void kv(StringBuilder sb, String k, String v) {
        sb.append("<tr><td>").append(k).append("</td><td>").append(v == null ? "-" : v).append("</td></tr>");
    }
}
