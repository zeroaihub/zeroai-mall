package com.zeroai.simulator.auth;

import com.zeroai.simulator.client.ApiResponse;
import com.zeroai.simulator.client.MobileApi;
import com.zeroai.simulator.config.SimConfig;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 账号预热。
 * <p>
 * 为什么要单独预热而不是运行时登录：
 * <ul>
 *   <li>{@code genMobileCode} 有发送冷却，并发登录会大面积命中限流；</li>
 *   <li>每次新手机号登录都会创建会员并发放注册券，1000 次登录就是 1000 条会员 + 一批券，
 *       会把压测基线搅乱。预热一次、token 复用，才能保证基线干净。</li>
 * </ul>
 * 预热并发度固定 16，目的是「稳」而不是「快」：这一步是写库操作，打太快会污染基线。
 */
public final class TokenWarmup {

    private static final int CONCURRENCY = 16;
    /** 非 prod 环境固定验证码，见 LoginController。 */
    private static final String DEV_SMS_CODE = "1234";
    /** 失败样本只打前 10 条，避免 1000 个失败刷爆控制台。 */
    private static final int MAX_FAILURE_SAMPLES = 10;

    private final AtomicInteger failureSamples = new AtomicInteger();

    private final SimConfig cfg;
    private final MobileApi mobile;
    private final AccountPool pool;

    public TokenWarmup(SimConfig cfg, MobileApi mobile, AccountPool pool) {
        this.cfg = cfg;
        this.mobile = mobile;
        this.pool = pool;
    }

    /**
     * 预热指定数量的账号（幂等：已存在的手机号会跳过）。
     *
     * @param size 目标账号数
     * @return 本次新建的账号数
     * @throws InterruptedException 线程被中断
     */
    public int warmup(int size) throws InterruptedException {
        ExecutorService poolExec = Executors.newFixedThreadPool(CONCURRENCY);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try {
            for (int i = 0; i < size; i++) {
                final int index = i;
                poolExec.submit(() -> {
                    String mobileNo = buildMobile(index);
                    if (pool.contains(mobileNo)) {
                        return;
                    }
                    try {
                        VirtualUser user = createOne(mobileNo);
                        if (user != null) {
                            pool.append(user);
                            created.incrementAndGet();
                            if (created.get() % 50 == 0) {
                                System.out.printf("  预热进度: 已创建 %d / %d%n", created.get(), size);
                            }
                        } else {
                            failed.incrementAndGet();
                        }
                    } catch (IOException e) {
                        failed.incrementAndGet();
                    }
                });
            }
            poolExec.shutdown();
            poolExec.awaitTermination(30, TimeUnit.MINUTES);
        } finally {
            poolExec.shutdownNow();
        }
        if (failed.get() > 0) {
            System.out.printf("  注意: %d 个账号预热失败，可重跑 warmup 补齐（幂等）%n", failed.get());
        }
        return created.get();
    }

    /**
     * 创建一个账号：发码 → 登录 → 确保有默认地址。
     * <p>
     * 失败必须把原因打出来：预热是最前置的一步，这里静默失败会让后面每一步都莫名其妙，
     * 之前就因为没有输出，一个 {@code /address/add} 路径写错被误判成「登录不通」。
     *
     * @param mobileNo 手机号
     * @return 虚拟用户，失败返回 null
     */
    private VirtualUser createOne(String mobileNo) {
        String token = login(mobileNo);
        if (token == null) {
            return null;
        }
        long addressId = ensureAddress(token, mobileNo);
        if (addressId <= 0) {
            reportFailure(mobileNo, "address", "地址创建/查询失败");
            return null;
        }
        return new VirtualUser(mobileNo, token, addressId);
    }

    /** 打印前若干个失败样本，避免几百个失败把控制台刷爆。 */
    private void reportFailure(String mobileNo, String step, String detail) {
        if (failureSamples.getAndIncrement() < MAX_FAILURE_SAMPLES) {
            System.out.printf("  [warmup 失败] mobile=%s step=%s detail=%s%n", mobileNo, step, detail);
        }
    }

    /**
     * 登录，失败重试一次。
     * <p>
     * 首次登录的失败大多是验证码还没落到 Redis（发码与登录有极短的时序差），
     * 重试一次即可，不做无限重试以免掩盖真实的接口问题。
     */
    private String login(String mobileNo) {
        for (int attempt = 0; attempt < 2; attempt++) {
            ApiResponse codeResp = mobile.genMobileCode(mobileNo);
            ApiResponse resp = mobile.login(mobileNo, DEV_SMS_CODE);
            if (resp.ok() && resp.data() != null) {
                return String.valueOf(resp.data());
            }
            if (attempt == 1) {
                reportFailure(mobileNo, "login",
                        "genCode=" + codeResp.errorTag() + " login=" + resp.errorTag());
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * 确保该用户存在收货地址，返回地址 ID。
     * <p>
     * 下单必须带 addressId，所以地址是账号预热的一部分，不能等到下单时才发现没地址。
     */
    private long ensureAddress(String token, String mobileNo) {
        ApiResponse list = mobile.addressList(token);
        long existing = firstAddressId(list);
        if (existing > 0) {
            return existing;
        }
        String suffix = mobileNo.length() >= 4 ? mobileNo.substring(mobileNo.length() - 4) : mobileNo;
        mobile.addAddress(token, "SIM-" + suffix, mobileNo);
        return firstAddressId(mobile.addressList(token));
    }

    private long firstAddressId(ApiResponse resp) {
        var arr = resp.dataArray();
        if (arr == null || arr.isEmpty()) {
            return 0;
        }
        Long id = arr.getJSONObject(0).getLong("id");
        return id == null ? 0 : id;
    }

    /**
     * 生成手机号。
     * <p>
     * 用专用号段，是为了压完之后能按号段精确清理，不碰真实用户数据。
     *
     * @param index 序号
     * @return 11 位手机号
     */
    private String buildMobile(int index) {
        int serial = cfg.mobileStart() + index;
        return cfg.mobilePrefix() + String.format("%04d", serial);
    }
}
