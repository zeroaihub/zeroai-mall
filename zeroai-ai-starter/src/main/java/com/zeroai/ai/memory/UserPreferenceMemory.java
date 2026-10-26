package com.zeroai.ai.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 用户偏好长期记忆。
 * <p>
 * 与 {@code HarnessAgent} 内置记忆的区别：
 * <ul>
 *   <li><b>会话记忆</b>（SDK 的 transcript / stateStore）随一次会话产生与消亡，
 *       用于「接着上一句说」，不属于长期资产。</li>
 *   <li><b>长期记忆</b>（本类）跨会话保留，只存「用户显式表达、且有必要长期记住的偏好」，
 *       例如「常买安卓手机」「偏好顺丰发货」。</li>
 * </ul>
 * 为什么不用 SDK 的 {@code memory_save} 工具：该工具的参数由模型自由生成，
 * 无法满足「用户明确允许才写入」这一前提。写入决策必须留在业务侧，
 * 模型只能建议，不能直接落盘。
 * <p>
 * 安全边界：
 * <ul>
 *   <li>目录按用户隔离，路径键做字符白名单过滤，避免路径穿越。</li>
 *   <li>写入前必经 {@link MemorySafetyGuard}，命中敏感规则直接拒绝。</li>
 *   <li>条目数封顶，避免被灌爆后污染 Prompt。</li>
 *   <li>不存放任何业务数据副本（订单、支付、地址），只存偏好文本。</li>
 * </ul>
 */
public class UserPreferenceMemory {

    private final Path baseDir;
    private final int maxEntries;

    /**
     * @param baseDir    长期记忆根目录，其下按用户分子目录
     * @param maxEntries 单用户条目上限
     */
    public UserPreferenceMemory(Path baseDir, int maxEntries) {
        this.baseDir = baseDir;
        this.maxEntries = Math.max(1, maxEntries);
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("长期记忆目录创建失败：" + baseDir, e);
        }
    }

    /**
     * 记录一条偏好。
     *
     * @param userId  用户 ID，必须来自服务端鉴权结果
     * @param content 偏好内容，由用户显式确认后传入
     * @return 写入结果
     */
    public WriteResult remember(long userId, String content) {
        MemorySafetyGuard.AuditResult audit = MemorySafetyGuard.audit(content);
        if (!audit.allowed()) {
            return new WriteResult(false, audit.reasons(), 0);
        }
        Path file = fileOf(userId);
        String line = System.currentTimeMillis() + "\t" + normalize(content);
        try {
            Files.createDirectories(file.getParent());
            List<String> existing = readLines(file);
            if (existing.size() >= maxEntries) {
                return new WriteResult(false, List.of("EXCEEDED_MAX_ENTRIES:" + maxEntries), existing.size());
            }
            if (existing.stream().anyMatch(l -> l.endsWith("\t" + normalize(content)))) {
                // 相同偏好重复表达不重复记录，避免占满配额
                return new WriteResult(true, List.of(), existing.size());
            }
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return new WriteResult(true, List.of(), existing.size() + 1);
        } catch (IOException e) {
            throw new UncheckedIOException("长期记忆写入失败：" + file, e);
        }
    }

    /**
     * 读取该用户的全部偏好，按写入时间从早到晚。
     *
     * @param userId 用户 ID
     * @return 偏好文本列表，无记录时返回空列表
     */
    public List<String> recall(long userId) {
        Path file = fileOf(userId);
        List<String> lines = readLines(file);
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            int tab = line.indexOf('\t');
            if (tab >= 0 && tab + 1 < line.length()) {
                result.add(line.substring(tab + 1));
            }
        }
        return result;
    }

    /**
     * 清空该用户的偏好，用于「用户要求删除我的数据」。
     *
     * @param userId 用户 ID
     * @return 是否确实删除了文件
     */
    public boolean forget(long userId) {
        try {
            return Files.deleteIfExists(fileOf(userId));
        } catch (IOException e) {
            throw new UncheckedIOException("长期记忆删除失败", e);
        }
    }

    private Path fileOf(long userId) {
        // userId 是 long，天然不含路径字符；仍保留校验，防止未来改成字符串标识
        if (userId <= 0) {
            throw new IllegalArgumentException("userId 非法：" + userId);
        }
        return baseDir.resolve(String.valueOf(userId)).resolve("preferences.tsv");
    }

    private static String normalize(String content) {
        // 单行存储：换行会破坏文件结构，也可能是「注入多行指令」的入口
        return content.trim().replaceAll("[\\r\\n]+", " ");
    }

    private static List<String> readLines(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            Deque<String> queue = new ArrayDeque<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    queue.add(line);
                }
            }
            return List.copyOf(queue);
        } catch (IOException e) {
            throw new UncheckedIOException("长期记忆读取失败：" + file, e);
        }
    }

    /**
     * 写入结果。
     *
     * @param stored    是否已写入（重复内容视为成功但未新增）
     * @param reasons   拒绝原因代码，成功时为空
     * @param entryCount 写入后该用户的条目数
     */
    public record WriteResult(boolean stored, List<String> reasons, int entryCount) {
    }
}
