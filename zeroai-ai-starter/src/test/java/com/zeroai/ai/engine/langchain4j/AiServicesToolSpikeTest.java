package com.zeroai.ai.engine.langchain4j;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段一 Spike：验证 LangChain4j 1.24 的 AiServices 在流式模式下能否自动执行工具并续流，
 * 并拿到真实事件时序，作为 {@code Lc4jAgentHandler} SSE 事件映射的依据。
 * <p>
 * <b>这不是常规单元测试</b>：需要真实模型与密钥，靠环境变量 {@code LC4J_SPIKE=true} 手动触发，
 * 常规 {@code mvn test} 直接跳过。配置从仓库根 {@code .env} 读取，不依赖 Spring 容器。
 */
class AiServicesToolSpikeTest {

    @Test
    @DisplayName("AiServices 流式工具调用：自动执行工具并续流，事件时序可映射 SSE")
    @EnabledIfEnvironmentVariable(named = "LC4J_SPIKE", matches = "true")
    void streamingToolCallEndToEnd() throws Exception {
        Map<String, String> env = loadEnv();
        StreamingChatModel chatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(LangChain4jEngineAutoConfiguration.resolveBaseUrl(env.get("LLM_BASE_URL")))
                .apiKey(env.get("LLM_API_KEY"))
                .modelName(env.get("LLM_MODEL"))
                .logRequests(false)
                .logResponses(false)
                .build();

        Assistant assistant = AiServices.builder(Assistant.class)
                .streamingChatModel(chatModel)
                .tools(new FakeTools())
                .build();

        // 时序记录：before → executed → delta* → complete 是预期形态
        List<String> timeline = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        assistant.chat("帮我找3000元以下的手机，然后查一下最便宜那个手机的库存")
                .onPartialResponse(delta -> timeline.add("delta:" + delta))
                .beforeToolExecution(before -> timeline.add(
                        "before:" + before.request().name() + " args=" + before.request().arguments()))
                .onToolExecuted(executed -> timeline.add(
                        "executed:" + executed.request().name() + " result=" + executed.result()))
                .onCompleteResponse(response -> {
                    timeline.add("complete");
                    done.countDown();
                })
                .onError(error -> {
                    failure.set(error);
                    done.countDown();
                })
                .start();

        assertTrue(done.await(120, TimeUnit.SECONDS), "120s 内未结束");
        if (failure.get() != null) {
            throw new AssertionError("流式失败", failure.get());
        }
        System.out.println("========== SPIKE TIMELINE ==========");
        timeline.forEach(line -> System.out.println("[spike] " + line));
        System.out.println("====================================");
    }

    /** AiServices 的服务接口：流式返回，框架内部处理工具循环。 */
    interface Assistant {

        TokenStream chat(String message);
    }

    /** 假工具：不依赖任何业务服务，只为验证框架的工具执行行为。 */
    static class FakeTools {

        @Tool(name = "search_products",
                value = "按关键词搜索在售商品，返回 JSON 数组，含 goodsId/name/price")
        String searchProducts(String keyword, int limit) {
            return """
                    {"count":2,"items":[
                      {"goodsId":1,"skuId":101,"name":"小米手机","price":2999},
                      {"goodsId":2,"skuId":102,"name":"苹果手机","price":5999}]}"""
                    .formatted(keyword, limit);
        }

        @Tool(name = "get_stock_status",
                value = "按 skuId 查询库存，返回 JSON")
        String getStockStatus(long skuId) {
            return "{\"skuId\":%d,\"available\":8,\"inStock\":true}".formatted(skuId);
        }
    }

    /** 解析仓库根 .env（KEY=VALUE），供 Spike 脱离 Spring 使用。 */
    private static Map<String, String> loadEnv() throws IOException {
        List<Path> candidates = List.of(
                Paths.get("../.env"),
                Paths.get(".env"));
        for (Path path : candidates) {
            if (Files.exists(path)) {
                Map<String, String> env = new HashMap<>();
                for (String line : Files.readAllLines(path)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                        continue;
                    }
                    int idx = trimmed.indexOf('=');
                    env.put(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim());
                }
                return env;
            }
        }
        throw new IllegalStateException("找不到 .env（Spike 需要真实模型配置）");
    }
}
