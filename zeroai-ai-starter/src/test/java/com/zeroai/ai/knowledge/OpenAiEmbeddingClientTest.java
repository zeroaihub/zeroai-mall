package com.zeroai.ai.knowledge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI 兼容向量化客户端测试。
 * <p>
 * 用 JDK 自带的 {@link HttpServer} 起一个本地假服务，不打外网、不 mock HTTP 库。
 * 重点验证三件真实集成里最容易踩的事：<b>data[] 顺序不可信</b>、
 * <b>维度不符必须立刻失败</b>、<b>没配密钥时不能发空 Authorization 头</b>。
 */
class OpenAiEmbeddingClientTest {

    /** 假响应构造器：入参是请求体，返回状态码与响应体。 */
    private record Responder(int status, String body) {
    }

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private Function<String, Responder> responder;

    @BeforeEach
    void startServer() throws IOException {
        responder = body -> new Responder(200, payload(new float[][]{{0.1f, 0.2f, 0.3f, 0.4f}}, false));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(body);
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        Responder result = responder.apply(body);
        byte[] bytes = result.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(result.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * 构造响应体。
     *
     * @param vectors   向量
     * @param reversed  是否把 data[] 逆序返回（模拟协议不保证顺序）
     * @return JSON
     */
    private static String payload(float[][] vectors, boolean reversed) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        int total = vectors.length;
        for (int i = 0; i < total; i++) {
            int position = reversed ? total - 1 - i : i;
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"index\":").append(position).append(",\"embedding\":[");
            float[] vector = vectors[position];
            for (int j = 0; j < vector.length; j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(vector[j]);
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    private KnowledgeProperties.Embedding config(int dimension) {
        KnowledgeProperties.Embedding config = new KnowledgeProperties.Embedding();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setApiKey("");
        config.setModel("fake-embedding");
        config.setDimension(dimension);
        config.setTimeoutMs(2000);
        return config;
    }

    @Test
    @DisplayName("正常响应：解析出向量，维度与配置一致")
    void shouldParseVector() {
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(config(4));

        float[] vector = client.embed("退款多久到账");

        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, vector, 1e-6f);
        assertEquals(4, client.dimension());
        assertEquals("fake-embedding", client.model());
        assertTrue(requests.get(0).contains("\"model\":\"fake-embedding\""));
    }

    @Test
    @DisplayName("data[] 乱序时按 index 重排，不能把 A 的向量当成 B 的")
    void shouldReorderByIndex() {
        responder = body -> new Responder(200, payload(
                new float[][]{{1f, 0f, 0f, 0f}, {0f, 2f, 0f, 0f}}, true));
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(config(4));

        List<float[]> vectors = client.embedAll(List.of("第一段", "第二段"));

        assertEquals(2, vectors.size());
        assertArrayEquals(new float[]{1f, 0f, 0f, 0f}, vectors.get(0), 1e-6f);
        assertArrayEquals(new float[]{0f, 2f, 0f, 0f}, vectors.get(1), 1e-6f);
    }

    @Test
    @DisplayName("维度不符立刻失败，并说清怎么改")
    void shouldFailOnDimensionMismatch() {
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(config(1024));

        KnowledgeEmbeddingException error = assertThrows(KnowledgeEmbeddingException.class,
                () -> client.embed("退款"));

        assertTrue(error.getMessage().contains("向量维度不符"), error.getMessage());
        assertTrue(error.getMessage().contains("embedding.dimension"), "错误信息要给出可执行的修复动作");
    }

    @Test
    @DisplayName("非 2xx 响应：抛异常且带上状态码")
    void shouldFailOnHttpError() {
        responder = body -> new Responder(401, "{\"error\":\"invalid api key\"}");
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(config(4));

        KnowledgeEmbeddingException error = assertThrows(KnowledgeEmbeddingException.class,
                () -> client.embed("退款"));

        assertTrue(error.getMessage().contains("401"), error.getMessage());
    }

    @Test
    @DisplayName("响应不是 JSON 或缺少 data：抛异常，不返回空向量")
    void shouldFailOnMalformedResponse() {
        responder = body -> new Responder(200, "{\"result\":\"ok\"}");
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(config(4));

        assertThrows(KnowledgeEmbeddingException.class, () -> client.embed("退款"));
    }

    @Test
    @DisplayName("没配密钥时不发 Authorization 头（本地 Ollama 会因此报错）")
    void shouldOmitAuthorizationWhenKeyBlank() {
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(config(4));

        client.embed("退款");

        assertNull(authorizations.get(0));
    }

    @Test
    @DisplayName("配了密钥时按 Bearer 发送")
    void shouldSendBearerToken() {
        KnowledgeProperties.Embedding config = config(4);
        config.setApiKey("sk-test");
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(config);

        client.embed("退款");

        assertEquals("Bearer sk-test", authorizations.get(0));
    }

    @Test
    @DisplayName("批量按 batch-size 拆批，减少往返次数")
    void shouldBatchRequests() {
        // 每个请求返回与 input 条数相同的向量
        responder = body -> {
            int size;
            try {
                size = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(body).path("input").size();
            } catch (Exception e) {
                size = 1;
            }
            size = Math.max(1, size);
            float[][] vectors = new float[size][4];
            for (int i = 0; i < size; i++) {
                vectors[i] = new float[]{i, 0f, 0f, 0f};
            }
            return new Responder(200, payload(vectors, false));
        };
        KnowledgeProperties.Embedding config = config(4);
        config.setBatchSize(2);
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(config);

        List<float[]> vectors = client.embedAll(List.of("a", "b", "c", "d", "e"));

        assertEquals(5, vectors.size());
        assertEquals(3, requests.size(), "5 条文本按每批 2 条应拆成 3 次请求");
    }

    @Test
    @DisplayName("base-url 为空时构造直接失败，避免运行期静默降级")
    void shouldRejectBlankBaseUrl() {
        KnowledgeProperties.Embedding config = new KnowledgeProperties.Embedding();
        config.setBaseUrl("  ");

        assertThrows(IllegalArgumentException.class, () -> new OpenAiEmbeddingClient(config));
        assertEquals(false, OpenAiEmbeddingClient.isConfigured(config));
        assertEquals(true, OpenAiEmbeddingClient.isConfigured(config(4)));
    }

    @Test
    @DisplayName("空文本不发起请求")
    void shouldRejectBlankText() {
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(config(4));

        assertThrows(KnowledgeEmbeddingException.class, () -> client.embed("   "));
        assertTrue(requests.isEmpty(), "空文本不该打网络");
    }

    @Test
    @DisplayName("超长文本按 max-chars 截断后再送，避免服务端 400")
    void shouldTruncateLongText() {
        KnowledgeProperties.Embedding config = config(4);
        config.setMaxChars(10);
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(config);

        client.embed("这是一个非常非常长的政策文本".repeat(10));

        assertTrue(requests.get(0).length() < 200, "请求体应被截断，实际长度=" + requests.get(0).length());
    }

    @Test
    @DisplayName("服务不可达时抛知识向量化异常，不抛底层 IOException")
    void shouldWrapConnectionFailure() {
        KnowledgeProperties.Embedding config = config(4);
        // 指向一个没人监听的端口：比 stop 掉共享 server 更干净，不影响其他用例
        config.setBaseUrl("http://127.0.0.1:1");
        config.setTimeoutMs(1000);
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(config);

        KnowledgeEmbeddingException error = assertThrows(KnowledgeEmbeddingException.class,
                () -> client.embed("退款"));
        // ConnectException 的 getMessage() 往往是 null，只打 null 等于没打
        assertFalse(error.getMessage().endsWith("null"), "错误信息必须能区分「连不上」和「证书错误」，实际="
                + error.getMessage());
        assertTrue(error.getMessage().length() > "向量化请求失败：".length() + 3, error.getMessage());
    }
}
