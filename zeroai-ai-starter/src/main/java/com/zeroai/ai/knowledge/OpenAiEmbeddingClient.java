package com.zeroai.ai.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OpenAI 兼容协议的向量化客户端。
 * <p>
 * 只依赖 JDK 自带的 {@link HttpClient} 与 Jackson，不引第三方 SDK：
 * embedding 接口的请求体只有 {@code {model, input}} 两个字段，为它引一套 SDK
 * 等于把一个稳定协议交给第三方版本节奏管理。
 * <p>
 * <b>为什么是「OpenAI 兼容」而不是绑定某一家</b>：阿里百炼、硅基流动、智谱、
 * Ollama、vLLM 都实现了 {@code POST /v1/embeddings}，一个客户端覆盖全部。
 * 换供应商只改 {@code base-url} 与 {@code model}，不动代码。
 * <p>
 * <b>响应校验比请求更重要</b>：这里做了三件容易漏的事——
 * <ol>
 *   <li>维度校验：配置 {@code dimension} 与服务返回不一致时立即失败。
 *       维度错位不会报错，只会让相似度变成噪声，最终表现为「政策全部查不到」，
 *       是这类集成最难排查的故障；</li>
 *   <li>批量结果按 {@code index} 重排：协议不保证 {@code data[]} 与入参同序，
 *       错位会让 A 文档的向量被当成 B 文档的；</li>
 *   <li>错误信息截断：服务端报错回显里可能带请求内容，日志只留前 200 字。</li>
 * </ol>
 */
public class OpenAiEmbeddingClient implements KnowledgeEmbedder {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);

    /** 错误响应最多记录多少字符。 */
    private static final int ERROR_BODY_LIMIT = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int dimension;
    private final int maxChars;
    private final int batchSize;
    private final int timeoutMs;

    /**
     * @param config 向量化配置；{@code base-url} 必须非空
     */
    public OpenAiEmbeddingClient(KnowledgeProperties.Embedding config) {
        String baseUrl = config.getBaseUrl() == null ? "" : config.getBaseUrl().trim();
        if (baseUrl.isEmpty()) {
            throw new IllegalArgumentException("embedding.base-url 未配置");
        }
        String path = config.getPath() == null || config.getPath().isBlank() ? "/v1/embeddings" : config.getPath().trim();
        this.endpoint = URI.create(stripTrailingSlash(baseUrl) + (path.startsWith("/") ? path : "/" + path));
        this.apiKey = config.getApiKey() == null ? "" : config.getApiKey().trim();
        this.model = config.getModel();
        this.dimension = config.getDimension();
        this.maxChars = Math.max(32, config.getMaxChars());
        this.batchSize = Math.max(1, config.getBatchSize());
        this.timeoutMs = Math.min(30_000, Math.max(1_000, config.getTimeoutMs()));
        this.http = HttpClient.newBuilder()
                // 连接超时比请求超时短：连不上要快速失败，读响应可以耐心一点
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 5000)))
                .build();
    }

    /**
     * 配置是否完整到可以创建客户端。
     *
     * @param config 向量化配置
     * @return {@code base-url} 非空返回 true
     */
    public static boolean isConfigured(KnowledgeProperties.Embedding config) {
        return config != null && config.getBaseUrl() != null && !config.getBaseUrl().isBlank();
    }

    @Override
    public float[] embed(String text) {
        List<float[]> vectors = doEmbed(List.of(text));
        return vectors.get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(from + batchSize, texts.size());
            result.addAll(doEmbed(texts.subList(from, to)));
        }
        return result;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String model() {
        return model;
    }

    /**
     * 发起一次（可能批量的）向量化请求。
     *
     * @param texts 本批文本，数量不超过 {@code batch-size}
     * @return 与入参同序的向量
     */
    private List<float[]> doEmbed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new KnowledgeEmbeddingException("向量化文本为空");
            }
            // 超长输入部分服务直接 400，这里按配置截断而不是让整批失败
            input.add(text.length() > maxChars ? text.substring(0, maxChars) : text);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (!apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KnowledgeEmbeddingException("向量化请求被中断", e);
        } catch (Exception e) {
            // getMessage() 对 ConnectException 之类的异常经常是 null，
            // 只打 null 等于没打——排障时无法区分「连不上」和「证书错误」
            throw new KnowledgeEmbeddingException("向量化请求失败：" + describe(e), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new KnowledgeEmbeddingException("向量化接口返回 " + response.statusCode() + "：" + abbreviate(response.body()));
        }
        return parse(response.body(), texts.size());
    }

    /**
     * 解析响应。
     *
     * @param payload 响应体
     * @param expected 期望条数
     * @return 与入参同序的向量
     */
    private List<float[]> parse(String payload, int expected) {
        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (Exception e) {
            throw new KnowledgeEmbeddingException("向量化响应不是合法 JSON：" + abbreviate(payload), e);
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() == 0) {
            throw new KnowledgeEmbeddingException("向量化响应缺少 data 数组：" + abbreviate(payload));
        }
        // 协议不保证 data[] 与入参同序，按 index 排回去；缺失 index 时按原序当作是对的。
        // 这里先包一层 (index, node)，不能依赖 items.indexOf —— 排序过程中下标会变。
        record Item(int index, JsonNode node) {
        }
        List<Item> ordered = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            JsonNode item = data.get(i);
            ordered.add(new Item(item.path("index").asInt(i), item));
        }
        ordered.sort(Comparator.comparingInt(Item::index));

        List<float[]> vectors = new ArrayList<>(expected);
        for (Item item : ordered) {
            JsonNode embedding = item.node().path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                throw new KnowledgeEmbeddingException("向量化响应中的 embedding 为空");
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            if (vector.length != dimension) {
                // 维度不符必须立刻失败：继续下去算出的相似度全是噪声，且不会有任何报错
                throw new KnowledgeEmbeddingException(
                        "向量维度不符：配置 dimension=" + dimension + "，模型实际返回 " + vector.length
                                + "（models=" + model + "）。请把 embedding.dimension 改成实际维度，"
                                + "或换一个维度匹配的模型");
            }
            vectors.add(vector);
        }
        if (vectors.size() != expected) {
            throw new KnowledgeEmbeddingException(
                    "向量化条数不符：期望 " + expected + "，实际返回 " + vectors.size());
        }
        return vectors;
    }

    /**
     * 异常转一句可读描述，消息为空时退化为类名。
     *
     * @param error 异常
     * @return 描述文本
     */
    private static String describe(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message;
    }

    /**
     * 截断错误内容，避免日志被超长回显刷屏或带出敏感信息。
     *
     * @param text 原始文本
     * @return 截断后的文本
     */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() > ERROR_BODY_LIMIT ? single.substring(0, ERROR_BODY_LIMIT) + "..." : single;
    }

    /**
     * 去掉地址末尾斜杠，避免拼出双斜杠。
     *
     * @param url 地址
     * @return 规范化地址
     */
    private static String stripTrailingSlash(String url) {
        String value = url;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
