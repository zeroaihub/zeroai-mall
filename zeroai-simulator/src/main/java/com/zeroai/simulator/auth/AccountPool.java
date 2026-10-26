package com.zeroai.simulator.auth;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 账号池。
 * <p>
 * 账号数（并发用户数）与工作线程数是两个独立维度：
 * 账号决定「有多少个不同身份在操作」，线程只决定同时在途的请求数。
 * 请求时按轮转方式取账号，让 1000 个用户都能被均匀使用，而不是被少数线程长期占用。
 * <p>
 * 落盘格式为 JSONL，一行一个用户，便于人工核对与增量补号。
 */
public final class AccountPool {

    private final List<VirtualUser> users = new ArrayList<>();
    private final Set<String> knownMobiles = new HashSet<>();
    private final AtomicInteger cursor = new AtomicInteger();
    /** 当前被租用（有一轮行为在跑）的用户下标。 */
    private final Set<Integer> busy = ConcurrentHashMap.newKeySet();
    private final Path file;

    public AccountPool(Path file) {
        this.file = file;
    }

    /**
     * 从文件加载已有账号。
     *
     * @throws IOException 读取失败
     */
    public void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            JSONObject o = JSON.parseObject(line);
            String mobile = o.getString("mobile");
            if (mobile == null) {
                continue;
            }
            VirtualUser u = new VirtualUser(mobile, o.getString("token"), o.getLongValue("addressId"));
            users.add(u);
            knownMobiles.add(mobile);
        }
    }

    /**
     * 追加一个账号并立即落盘，避免预热到一半中断导致账号与 token 不一致。
     *
     * @param user 虚拟用户
     * @throws IOException 写入失败
     */
    public synchronized void append(VirtualUser user) throws IOException {
        users.add(user);
        knownMobiles.add(user.mobile());
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        JSONObject o = new JSONObject();
        o.put("mobile", user.mobile());
        o.put("token", user.token());
        o.put("addressId", user.addressId());
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(o.toJSONString());
            w.newLine();
        }
    }

    public boolean contains(String mobile) {
        return knownMobiles.contains(mobile);
    }

    public int size() {
        return users.size();
    }

    public Path file() {
        return file;
    }

    /**
     * 轮转取下一个用户。
     * <p>
     * 只用于「同一用户不会同时在跑的」场景（如齐射，一人一次）。
     * 阶梯压测请用 {@link #lease()}：直接用本方法会让多个线程同时驱动同一个用户，
     * 制造出现实中不存在的并发（同一购物车被两个线程抢着下单/删除）。
     *
     * @return 虚拟用户
     * @throws IllegalStateException 账号池为空
     */
    public VirtualUser next() {
        if (users.isEmpty()) {
            throw new IllegalStateException("账号池为空，请先执行 warmup: " + file.toAbsolutePath());
        }
        int idx = Math.floorMod(cursor.getAndIncrement(), users.size());
        return users.get(idx);
    }

    /**
     * 租用一个当前空闲的用户，保证「一个用户同一时刻只有一轮行为在跑」。
     * <p>
     * 为什么必须互斥：真实用户不会同时下两个单。若不互斥，同一账号会被多个工作线程同时驱动，
     * 于是出现「A 线程刚加购、B 线程下单把购物车消费掉、A 线程再查 cartId 已不存在」这类
     * 自造故障，压出来的失败全是压测端噪声，不是系统问题。
     * <p>
     * 副作用要说清楚：账号数因此成为「并行行为」的上限。想压更高的 QPS，
     * 要么增加账号数，要么缩短单轮链路 —— 这正是「N 个用户并发」的真实含义。
     *
     * @return 空闲用户；全部在忙时返回 null（调用方应短暂退避后重试）
     */
    public VirtualUser lease() {
        int n = users.size();
        if (n == 0) {
            throw new IllegalStateException("账号池为空，请先执行 warmup: " + file.toAbsolutePath());
        }
        int start = cursor.getAndIncrement();
        for (int i = 0; i < n; i++) {
            int idx = Math.floorMod(start + i, n);
            if (busy.add(idx)) {
                return users.get(idx);
            }
        }
        return null;
    }

    /**
     * 归还租用的用户。
     *
     * @param user 之前通过 {@link #lease()} 取得的用户
     */
    public void release(VirtualUser user) {
        int idx = indexOf(user);
        if (idx >= 0) {
            busy.remove(idx);
        }
    }

    private int indexOf(VirtualUser user) {
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i).mobile().equals(user.mobile())) {
                return i;
            }
        }
        return -1;
    }

    public VirtualUser first() {
        if (users.isEmpty()) {
            throw new IllegalStateException("账号池为空，请先执行 warmup: " + file.toAbsolutePath());
        }
        return users.get(0);
    }

    public List<VirtualUser> all() {
        return List.copyOf(users);
    }
}
