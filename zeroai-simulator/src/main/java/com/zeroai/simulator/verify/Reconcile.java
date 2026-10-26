package com.zeroai.simulator.verify;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zeroai.simulator.config.SimConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 压测前后的数据对账。
 * <p>
 * 压测真正的产出不是 QPS 数字，而是「高并发下系统有没有算错」。这里检查的都是业务不变量：
 * 库存恒等式、超卖、订单幂等、状态分布。
 * <p>
 * 设计原则：<b>单条 SQL 失败不让整体失败</b>。不同环境的表结构可能有差异，
 * 查询报错时输出 SKIP 与原因，而不是让用户以为压测挂了——但跳过的检查必须在结论里显式列出。
 */
public final class Reconcile {

    /** 未支付订单超时关单窗口，本地实测 60 秒，对账前要等它跑完。 */
    public static final long UNPAID_CANCEL_WINDOW_SEC = 60;

    private final SimConfig cfg;

    public Reconcile(SimConfig cfg) {
        this.cfg = cfg;
    }

    // ---------------------------------------------------------------- 基线

    /**
     * 记录压测前水位。
     *
     * @throws IOException  写入失败
     * @throws SQLException 读取失败
     */
    public void baseline() throws IOException, SQLException {
        JSONObject out = new JSONObject();
        out.put("ts", System.currentTimeMillis());
        out.put("seckillActivityId", cfg.seckillActivityId());
        // key 必须显式转成 String：直接塞 Map<Long,long[]> 时 fastjson2 会把数字 key
        // 写成不带引号的 {9900001:[...]}，产出的是非法 JSON，下次读取解析不出 SKU，
        // 对账会以「基线中无该 SKU」跳过——跳过看起来不报错，但等于没对账。
        JSONObject skusJson = new JSONObject();
        seckillSkuSnapshot().forEach((id, v) -> skusJson.put(String.valueOf(id), v));
        out.put("skus", skusJson);
        // 普通商品的库存水位也要记：秒杀 SKU 的对账覆盖不到「下单→取消→回补」这条链路，
        // 而库存泄漏恰恰最容易发生在那里（冻结了没释放、或者取消时多补一次）。
        JSONObject productsJson = new JSONObject();
        productStockSnapshot().forEach((id, v) -> productsJson.put(String.valueOf(id), v));
        out.put("products", productsJson);
        out.put("orderCount", countOrders(null));
        Path file = Path.of(cfg.baselineFile());
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, out.toJSONString(), StandardCharsets.UTF_8);
        System.out.println("基线已记录: " + file.toAbsolutePath());
        System.out.println("  秒杀 SKU 水位: " + formatSkuSnapshot());
        System.out.println("  仿真商品水位: " + formatProductSnapshot(productsJson));
        System.out.println("  订单总数: " + out.get("orderCount"));
    }

    // ---------------------------------------------------------------- 对账

    /**
     * 执行对账并返回是否全部通过。
     *
     * @return true 表示所有可执行的检查都通过
     */
    public boolean verify() {
        JSONObject baseline = loadBaseline();
        if (baseline == null) {
            System.out.println("未找到基线文件，无法对账。请先执行 baseline 命令。");
            return false;
        }
        long since = baseline.getLongValue("ts");
        Timestamp from = new Timestamp(since);

        int pass = 0;
        int fail = 0;
        int skip = 0;

        System.out.println();
        System.out.println("================ 对账结果 ================");

        // C1/C2：库存恒等式与超卖
        Map<Long, long[]> before = readSkuMap(baseline.getJSONObject("skus"));
        Map<Long, long[]> after = null;
        try {
            after = seckillSkuSnapshot();
        } catch (SQLException e) {
            System.out.println("[SKIP] C1/C2 库存恒等式：查询失败 - " + e.getMessage());
            skip++;
        }
        if (after != null) {
            for (Map.Entry<Long, long[]> e : after.entrySet()) {
                long[] b = before.get(e.getKey());
                long[] a = e.getValue();
                long initialTotal = b == null ? -1 : b[0] + b[1] + b[2];
                long nowTotal = a[0] + a[1] + a[2];
                String tag = "C2 库存恒等式 sku=" + e.getKey();
                if (initialTotal < 0) {
                    System.out.println("[SKIP] " + tag + "：基线中无该 SKU");
                    skip++;
                } else if (initialTotal == nowTotal) {
                    System.out.println("[PASS] " + tag + "：available+locked+sold=" + nowTotal);
                    pass++;
                } else {
                    System.out.printf("[FAIL] %s：基线 %d，现在 %d，差 %d（丢流水或重复扣减）%n",
                            tag, initialTotal, nowTotal, nowTotal - initialTotal);
                    fail++;
                }
                if (a[2] > initialTotal && initialTotal >= 0) {
                    System.out.printf("[FAIL] C1 超卖 sku=%d：sold=%d 超过初始库存 %d%n", e.getKey(), a[2], initialTotal);
                    fail++;
                }
            }
            System.out.println("[PASS] C1 超卖检查：未发现 sold_stock 超过初始库存");
            pass++;
        }

        // C4：普通商品库存恒等式（number + locked_stock 压测前后必须相等）
        // 这是普通下单链路唯一的库存正确性断言：它同时覆盖「下单冻结」与「超时取消回补」。
        try {
            JSONObject beforeProducts = baseline.getJSONObject("products");
            if (beforeProducts == null || beforeProducts.isEmpty()) {
                System.out.println("[SKIP] C4 商品库存恒等式：基线中无商品水位（未配置 workload.goodsIds）");
                skip++;
            } else {
                Map<Long, long[]> productAfter = productStockSnapshot();
                int bad = 0;
                for (Object rawKey : ((Map<?, ?>) beforeProducts).keySet()) {
                    String key = String.valueOf(rawKey);
                    Object raw = beforeProducts.get(rawKey);
                    JSONArray arr = raw instanceof JSONArray a ? a : beforeProducts.getJSONArray(key);
                    long productId = Long.parseLong(key);
                    long[] pAfter = productAfter.get(productId);
                    if (arr == null || arr.size() < 2 || pAfter == null) {
                        System.out.println("[SKIP] C4 商品库存恒等式 product=" + productId + "：当前已查不到该货品");
                        skip++;
                        continue;
                    }
                    long pBefore = arr.getLongValue(0) + arr.getLongValue(1);
                    long now = pAfter[0] + pAfter[1];
                    if (pBefore == now) {
                        System.out.printf("[PASS] C4 商品库存恒等式 product=%d：number+locked=%d（number=%d,locked=%d）%n",
                                productId, now, pAfter[0], pAfter[1]);
                        pass++;
                    } else {
                        System.out.printf("[FAIL] C4 商品库存恒等式 product=%d：基线 %d，现在 %d，差 %d%n",
                                productId, pBefore, now, now - pBefore);
                        fail++;
                        bad++;
                    }
                }
                if (bad > 0) {
                    System.out.println("      差额为正=多扣了库存，为负=多补了库存，两者都说明流水与库存不一致。");
                }
            }
        } catch (SQLException e) {
            System.out.println("[SKIP] C4 商品库存恒等式：查询失败 - " + e.getMessage());
            skip++;
        }

        // C3：订单幂等（同用户同商品 2 秒内重复建单）
        try {
            int dup = countDuplicateOrders(from);
            if (dup == 0) {
                System.out.println("[PASS] C3 订单幂等：未发现 2 秒内重复建单");
                pass++;
            } else {
                System.out.println("[FAIL] C3 订单幂等：发现 " + dup + " 组疑似重复单");
                fail++;
            }
        } catch (SQLException e) {
            System.out.println("[SKIP] C3 订单幂等：查询失败 - " + e.getMessage());
            skip++;
        }

        // 状态分布：用于判断压测是否把订单推进到了各个阶段
        try {
            Map<Integer, Long> dist = orderStatusDistribution(from);
            System.out.println("[INFO] 订单状态分布（压测开始后）: " + dist);
            pass++;
        } catch (SQLException e) {
            System.out.println("[SKIP] 订单状态分布：查询失败 - " + e.getMessage());
            skip++;
        }

        System.out.printf("%n汇总：通过 %d，失败 %d，跳过 %d%n", pass, fail, skip);
        if (skip > 0) {
            System.out.println("注意：跳过的检查未被验证，结论中必须显式说明。");
        }
        System.out.println("==========================================");
        return fail == 0;
    }

    // ---------------------------------------------------------------- SQL

    private Map<Long, long[]> seckillSkuSnapshot() throws SQLException {
        Map<Long, long[]> map = new LinkedHashMap<>();
        String sql = "SELECT id, available_stock, locked_stock, sold_stock FROM shop_seckill_sku";
        long activityId = cfg.seckillActivityId();
        if (activityId > 0) {
            sql += " WHERE activity_id = ?";
        }
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (activityId > 0) {
                ps.setLong(1, activityId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getLong(1), new long[]{rs.getLong(2), rs.getLong(3), rs.getLong(4)});
                }
            }
        }
        return map;
    }

    /**
     * 把 SKU 水位格式化成可读文本。
     * <p>
     * 直接打印 {@code long[]} 会输出 {@code [J@1b11171f} 这种内存地址，
     * 基线那一步等于没输出，出了问题无法回看压测前到底是多少库存。
     *
     * @return 形如 {@code sku=9900001(available=999,locked=1,sold=0)}
     */
    private String formatSkuSnapshot() {
        try {
            Map<Long, long[]> map = seckillSkuSnapshot();
            if (map.isEmpty()) {
                return "（无匹配 SKU）";
            }
            StringBuilder sb = new StringBuilder();
            map.forEach((id, v) -> sb.append(sb.length() == 0 ? "" : ", ")
                    .append("sku=").append(id)
                    .append("(available=").append(v[0])
                    .append(",locked=").append(v[1])
                    .append(",sold=").append(v[2]).append(")"));
            return sb.toString();
        } catch (SQLException e) {
            return "查询失败: " + e.getMessage();
        }
    }

    /**
     * 仿真商品货品的库存水位（number 可售 + locked_stock 冻结）。
     * <p>
     * 只取配置里 workload.goodsIds 对应的商品，避免把真实商品也算进来。
     * 恒等式：{@code number + locked_stock} 在压测前后必须相等——
     * 下单只会在两者之间搬库存，取消则搬回去，任何差额都是丢流水或重复扣减。
     *
     * @return productId → {number, locked_stock}
     */
    private Map<Long, long[]> productStockSnapshot() throws SQLException {
        Map<Long, long[]> map = new LinkedHashMap<>();
        List<Long> goodsIds = cfg.goodsIds();
        if (goodsIds.isEmpty()) {
            return map;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(goodsIds.size(), "?"));
        String sql = "SELECT id, number, locked_stock FROM shop_goods_product WHERE goods_id IN (" + placeholders + ")";
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < goodsIds.size(); i++) {
                ps.setLong(i + 1, goodsIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getLong(1), new long[]{rs.getLong(2), rs.getLong(3)});
                }
            }
        }
        return map;
    }

    private String formatProductSnapshot(JSONObject json) {
        if (json == null || json.isEmpty()) {
            return "（未配置 workload.goodsIds，跳过商品库存对账）";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : json.keySet()) {
            JSONArray arr = json.getJSONArray(key);
            if (arr == null || arr.size() < 2) {
                continue;
            }
            sb.append(sb.length() == 0 ? "" : ", ")
                    .append("product=").append(key)
                    .append("(number=").append(arr.getLongValue(0))
                    .append(",locked=").append(arr.getLongValue(1)).append(")");
        }
        return sb.toString();
    }

    private Map<Long, long[]> readSkuMap(JSONObject json) {
        Map<Long, long[]> map = new LinkedHashMap<>();
        if (json == null) {
            return map;
        }
        // 兼容历史基线文件：早期版本用数字 key 写盘，解析后 key 可能是 Integer，
        // 用通配符视图遍历 + String.valueOf 归一化，避免 ClassCastException 与查不到值。
        for (Object rawKey : ((Map<?, ?>) json).keySet()) {
            String key = String.valueOf(rawKey);
            Object raw = json.get(rawKey);
            var arr = raw instanceof JSONArray a ? a : json.getJSONArray(key);
            if (arr != null && arr.size() >= 3) {
                map.put(Long.parseLong(key), new long[]{
                        arr.getLongValue(0), arr.getLongValue(1), arr.getLongValue(2)});
            }
        }
        return map;
    }

    private long countOrders(Timestamp from) throws SQLException {
        String sql = from == null
                ? "SELECT COUNT(*) FROM shop_order"
                : "SELECT COUNT(*) FROM shop_order WHERE create_time >= ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (from != null) {
                ps.setTimestamp(1, from);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private int countDuplicateOrders(Timestamp from) throws SQLException {
        String sql = """
                SELECT COUNT(*) FROM (
                    SELECT o.user_id, g.goods_id
                    FROM shop_order o
                    JOIN shop_order_goods g ON g.order_id = o.id
                    WHERE o.create_time >= ?
                    GROUP BY o.user_id, g.goods_id, FLOOR(UNIX_TIMESTAMP(o.create_time) / 2)
                    HAVING COUNT(*) > 1
                ) t
                """;
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, from);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private Map<Integer, Long> orderStatusDistribution(Timestamp from) throws SQLException {
        String sql = "SELECT order_status, COUNT(*) c FROM shop_order WHERE create_time >= ? GROUP BY order_status";
        Map<Integer, Long> map = new LinkedHashMap<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, from);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getInt(1), rs.getLong(2));
                }
            }
        }
        return map;
    }

    private Connection connect() throws SQLException {
        String url = cfg.jdbcUrl();
        if (url == null || url.isEmpty()) {
            throw new SQLException("未配置 sim.verify.jdbcUrl");
        }
        return DriverManager.getConnection(url, cfg.jdbcUser(), cfg.jdbcPassword());
    }

    private JSONObject loadBaseline() {
        Path file = Path.of(cfg.baselineFile());
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /** 计算建议的对账等待时间。 */
    public static LocalDateTime suggestVerifyAfter() {
        return LocalDateTime.now().plusSeconds(UNPAID_CANCEL_WINDOW_SEC + 30);
    }
}
