package com.zeroai.simulator.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 仿真客户端配置读取器。
 * <p>
 * 只用 snakeyaml 把文件读成嵌套 Map，再按点路径取值，不引入 Spring 容器。
 * 这样压测端与业务服务的依赖完全隔离：业务侧 yml 结构变化不会影响压测端启动。
 */
public final class SimConfig {

    private final Map<String, Object> root;
    private final Path source;

    private SimConfig(Map<String, Object> root, Path source) {
        this.root = root;
        this.source = source;
    }

    /**
     * 加载配置文件。
     *
     * @param file 配置文件路径
     * @return 配置对象
     * @throws IOException 文件不存在或解析失败
     */
    @SuppressWarnings("unchecked")
    public static SimConfig load(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("配置文件不存在: " + file.toAbsolutePath());
        }
        try (InputStream in = Files.newInputStream(file)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map)) {
                throw new IOException("配置文件格式错误，顶层必须是 map: " + file);
            }
            return new SimConfig((Map<String, Object>) loaded, file);
        }
    }

    public Path source() {
        return source;
    }

    // ---------------------------------------------------------------- 基础寻址

    /**
     * 按点路径取原始值，例如 "sim.account.size"。
     */
    private Object dig(String path) {
        String[] parts = path.split("\\.");
        Object cur = root;
        for (String part : parts) {
            if (!(cur instanceof Map<?, ?> map)) {
                return null;
            }
            cur = map.get(part);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    private String str(String path, String def) {
        Object v = dig(path);
        return v == null ? def : String.valueOf(v);
    }

    private int i32(String path, int def) {
        Object v = dig(path);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return v == null ? def : Integer.parseInt(String.valueOf(v).trim());
    }

    private long i64(String path, long def) {
        Object v = dig(path);
        if (v instanceof Number n) {
            return n.longValue();
        }
        return v == null ? def : Long.parseLong(String.valueOf(v).trim());
    }

    private boolean bool(String path, boolean def) {
        Object v = dig(path);
        if (v instanceof Boolean b) {
            return b;
        }
        return v == null ? def : Boolean.parseBoolean(String.valueOf(v).trim());
    }

    private double dbl(String path, double def) {
        Object v = dig(path);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return v == null ? def : Double.parseDouble(String.valueOf(v).trim());
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(String path) {
        Object v = dig(path);
        return v instanceof List ? (List<Object>) v : Collections.emptyList();
    }

    // ---------------------------------------------------------------- 连接地址

    public String baseUrl() {
        return stripTrailingSlash(str("sim.baseUrl", "http://127.0.0.1:82"));
    }

    public String adminBaseUrl() {
        return stripTrailingSlash(str("sim.adminBaseUrl", "http://127.0.0.1:81"));
    }

    private static String stripTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // ---------------------------------------------------------------- 多进程分片

    public int processIndex() {
        return i32("sim.processIndex", 0);
    }

    public int processCount() {
        return Math.max(1, i32("sim.processCount", 1));
    }

    // ---------------------------------------------------------------- 账号池

    public String accountPoolFile() {
        return str("sim.account.poolFile", "sim-data/accounts-local.jsonl");
    }

    public int accountSize() {
        return i32("sim.account.size", 200);
    }

    public String mobilePrefix() {
        return str("sim.account.mobilePrefix", "1990000");
    }

    /** 手机号起始序号，重跑时从上次结束的位置继续，避免重复注册。 */
    public int mobileStart() {
        return i32("sim.account.mobileStart", 1);
    }

    public String adminUsername() {
        return str("sim.account.adminUsername", "admin");
    }

    public String adminPassword() {
        return str("sim.account.adminPassword", "");
    }

    /**
     * 后台登录态。
     * <p>
     * 后台登录要过图形验证码，无法可靠自动化，因此直接由配置注入一个已登录的 token，
     * warmup 阶段用 {@code /getInfo} 校验。仅当需要压「发货 → 收货」链路时才必填。
     */
    public String adminToken() {
        return str("sim.account.adminToken", "");
    }

    // ---------------------------------------------------------------- 业务负载

    public String scenario() {
        return str("sim.workload.scenario", "search_only");
    }

    public long thinkMinMs() {
        return i64("sim.workload.thinkTime.minMs", 200);
    }

    public long thinkMaxMs() {
        return i64("sim.workload.thinkTime.maxMs", 5000);
    }

    public long thinkMedianMs() {
        return i64("sim.workload.thinkTime.medianMs", 800);
    }

    /**
     * 轮询异步下单结果的最大次数。
     * <p>
     * 不能照搬「2 秒」这类经验值当常量：下单链路里有一段 local_message 补偿表转发，
     * 转发线程是定时扫描的，本地实测端到端要 3~4 秒。窗口设太短会把
     * 「系统慢」误判成「系统错」，压出来的成功率毫无意义。
     * 默认 30 次 × 500ms = 15 秒。
     */
    public int orderPollMaxAttempts() {
        return (int) i64("sim.workload.orderPoll.maxAttempts", 30);
    }

    /** 轮询间隔毫秒，默认 500。 */
    public long orderPollIntervalMs() {
        return i64("sim.workload.orderPoll.intervalMs", 500);
    }

    /**
     * 加购后放弃的比例。
     * <p>
     * 保留这个分支是为了贴合真实漏斗（约三成用户加购后走开），
     * 但做「链路是否走得通」的验证时必须设成 0，否则每次 smoke 有 30% 概率
     * 停在中途，看起来像失败。压测用 0.3，smoke 用 0。
     */
    public double abandonAfterCartRate() {
        return dbl("sim.workload.abandonAfterCartRate", 0.30);
    }

    /**
     * 下单后不支付的比例（用于覆盖未支付超时关单 + 库存回补链路）。
     * <p>
     * 同理：验证链路要设 0，压测用 0.15。
     */
    public double leaveUnpaidRate() {
        return dbl("sim.workload.leaveUnpaidRate", 0.15);
    }

    public List<String> keywords() {
        List<String> result = new ArrayList<>();
        for (Object o : list("sim.workload.keywords")) {
            if (o != null) {
                result.add(String.valueOf(o));
            }
        }
        if (result.isEmpty()) {
            result.add("手机");
        }
        return result;
    }

    public List<Long> goodsIds() {
        List<Long> result = new ArrayList<>();
        for (Object o : list("sim.workload.goodsIds")) {
            if (o instanceof Number n) {
                result.add(n.longValue());
            } else if (o != null) {
                result.add(Long.parseLong(String.valueOf(o).trim()));
            }
        }
        return result;
    }

    public long seckillActivityId() {
        return i64("sim.workload.seckill.activityId", 0);
    }

    public long seckillActivitySkuId() {
        return i64("sim.workload.seckill.activitySkuId", 0);
    }

    public int seckillBurstUsers() {
        return i32("sim.workload.seckill.burstUsers", 100);
    }

    /** 是否在支付后继续走后台发货 + 用户确认收货，默认关闭以降低压测噪声。 */
    public boolean shipAndConfirm() {
        return bool("sim.workload.shipAndConfirm", false);
    }

    // ---------------------------------------------------------------- 压力模型

    public String mode() {
        return str("sim.load.mode", "open_loop");
    }

    public long tickMs() {
        return i64("sim.load.tickMs", 10);
    }

    /**
     * 同时在途请求上限（工作线程数）。
     * 注意：它不等于并发用户数，QPS 由令牌桶 λ 决定，线程数只决定「能同时在途多少请求」。
     */
    public int workers() {
        return Math.max(1, i32("sim.load.workers", 200));
    }

    public long burstAtEpochMs() {
        return i64("sim.load.burstAtEpochMs", 0);
    }

    /**
     * 阶梯负载：每档持续时长与目标 QPS。
     */
    public List<Stage> stages() {
        List<Stage> result = new ArrayList<>();
        for (Object o : list("sim.load.stages")) {
            if (o instanceof Map<?, ?> map) {
                long duration = toLong(map.get("duration"), 60);
                int qps = (int) toLong(map.get("targetQps"), 10);
                result.add(new Stage(duration, qps));
            }
        }
        if (result.isEmpty()) {
            result.add(new Stage(60, 10));
        }
        return result;
    }

    private static long toLong(Object v, long def) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.endsWith("s")) {
            s = s.substring(0, s.length() - 1);
        }
        return Long.parseLong(s);
    }

    // ---------------------------------------------------------------- HTTP

    public long connectTimeoutMs() {
        return i64("sim.http.connectTimeoutMs", 1000);
    }

    public long readTimeoutMs() {
        return i64("sim.http.readTimeoutMs", 5000);
    }

    /** 连接池上限，建议 ≥ workers。 */
    public int httpMaxIdle() {
        return i32("sim.http.maxIdleConnections", Math.max(32, workers()));
    }

    public long reportIntervalSec() {
        return i64("sim.report.intervalSec", 5);
    }

    public String outDir() {
        return str("sim.report.outDir", "sim-data");
    }

    // ---------------------------------------------------------------- 对账

    public boolean verifyEnabled() {
        return bool("sim.verify.enabled", false);
    }

    public String jdbcUrl() {
        return str("sim.verify.jdbcUrl", "");
    }

    public String jdbcUser() {
        return str("sim.verify.jdbcUser", "root");
    }

    public String jdbcPassword() {
        return str("sim.verify.jdbcPassword", "");
    }

    public String baselineFile() {
        return str("sim.verify.baselineFile", "sim-data/baseline.json");
    }

    /**
     * 一档阶梯负载。
     *
     * @param durationSec 持续秒数
     * @param targetQps   目标到达率
     */
    public record Stage(long durationSec, int targetQps) {
    }
}
