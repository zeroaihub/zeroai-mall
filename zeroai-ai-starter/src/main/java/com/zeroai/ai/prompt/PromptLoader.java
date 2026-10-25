package com.zeroai.ai.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Prompt 加载器。
 * <p>
 * System Prompt 一律外置到 classpath 下的文本文件，不写在 Java 字符串里：
 * 这样 Prompt 可以版本化、可 diff、可回归，改文案不需要重新编译。
 */
public final class PromptLoader {

    private PromptLoader() {
    }

    /**
     * 读取 classpath 下的 Prompt 文件。
     *
     * @param location 支持 {@code prompts/xxx.st} 与 {@code classpath:prompts/xxx.st} 两种写法
     * @return Prompt 文本（UTF-8）
     */
    public static String load(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("System Prompt 位置未配置");
        }
        String path = location.startsWith("classpath:") ? location.substring("classpath:".length()) : location;
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("System Prompt 文件不存在：" + path);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            return StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("System Prompt 读取失败：" + path, e);
        }
    }
}
