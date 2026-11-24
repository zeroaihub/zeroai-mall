package com.zeroai.ai.engine.langchain4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 架构守护测试：LangChain4j 包与 AgentScope / AgentScope 版工具的隔离断言。
 * <p>
 * 需求基线明确要求「LangChain4j 不和之前的代码耦合在一起」（ADR-003）。
 * 耦合最有渗透力的方式就是一条不起眼的 import，所以这里直接扫描源码文件，
 * 把「禁止 import」固化成测试——比口头约定和 code review 都可靠。
 * <p>
 * 允许的共享（业务层）：{@code domain.api}（商城服务）、{@code mall.event}（SSE 事件协议）、
 * {@code mall.proposal}（提议服务）、{@code mall.port}（检索端口）、{@code history/knowledge/web/config/prompt}
 * （模块内公共设施）。禁止的只有框架编排层：AgentScope 类型与 AgentScope 版工具包。
 */
class LangChain4jIsolationGuardTest {

    /** LangChain4j 包内禁止出现的 import 前缀：编排层完全隔离。 */
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "io.agentscope.",
            "com.zeroai.ai.mall.tool.");

    @Test
    @DisplayName("langchain4j 包不得 import AgentScope 类型与 AgentScope 版工具")
    void langchain4jPackageStaysIsolated() throws IOException {
        Path root = locateMainJavaRoot();
        Path lc4jRoot = root.resolve("com/zeroai/ai/engine/langchain4j");
        assertTrue(Files.isDirectory(lc4jRoot), "找不到 langchain4j 包目录：" + lc4jRoot);

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(lc4jRoot)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        String content = readQuietly(path);
                        for (String line : content.split("\n")) {
                            String trimmed = line.trim();
                            for (String forbidden : FORBIDDEN_IMPORTS) {
                                if (trimmed.startsWith("import " + forbidden)) {
                                    violations.add(path.getFileName() + " -> " + trimmed);
                                }
                            }
                        }
                    });
        }
        assertTrue(violations.isEmpty(),
                "LangChain4j 包出现被禁止的 import（编排层必须与 AgentScope 隔离）：" + violations);
    }

    /**
     * 定位 main 源码根目录。测试工作目录可能是模块目录，向上回溯两层。
     *
     * @return {@code src/main/java} 的绝对路径
     */
    private static Path locateMainJavaRoot() {
        List<Path> candidates = List.of(
                Paths.get("src/main/java"),
                Paths.get("zeroai-ai-starter/src/main/java"),
                Paths.get("../zeroai-ai-starter/src/main/java"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 zeroai-ai-starter/src/main/java");
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败：" + path, e);
        }
    }
}
