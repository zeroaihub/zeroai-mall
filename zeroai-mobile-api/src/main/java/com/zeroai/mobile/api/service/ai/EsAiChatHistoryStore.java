package com.zeroai.mobile.api.service.ai;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.zeroai.ai.config.AiHistoryProperties;
import com.zeroai.ai.history.AiChatExchange;
import com.zeroai.ai.history.AiChatHistoryStore;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 移动端对话历史的 ES 存储实现。
 * <p>
 * 为什么落在入口模块而不是 AI starter：ES 客户端属于具体中间件，
 * starter 的定位是可复用的 AI 能力包，不能反向绑定某个存储。
 * 这里只实现 {@link AiChatHistoryStore} 这一个 SPI，其余链路不认识 ES。
 * <p>
 * 索引约定（{@code zeroai.ai.history.index}，默认 {@code zeroai_ai_chat}）：
 * <ul>
 *   <li>首次使用自动建索引，单分片零副本——开发环境是单节点，多副本只会一直 yellow；</li>
 *   <li>{@code userId} 参与每次查询，任何读写都不提供「按文档 ID 直取」的入口；</li>
 *   <li>写入立即 refresh，保证用户答完刷新页面就能看到这一条。</li>
 * </ul>
 * 所有异常在实现内部吞掉并记 warn：历史是旁路能力，ES 挂了也不能让对话失败。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "zeroai.ai", name = "enabled", havingValue = "true")
public class EsAiChatHistoryStore implements AiChatHistoryStore {

    /** 归属用户字段，所有查询都必须带上它。 */
    private static final String FIELD_USER_ID = "userId";

    /** 记录时间字段，排序用。 */
    private static final String FIELD_CREATED_AT = "createdAt";

    /** 索引结构：显式声明类型，避免动态映射把 userId 猜成 keyword、把时间猜成 text。 */
    private static final String INDEX_MAPPING = """
            {
              "properties": {
                "userId":    { "type": "long" },
                "sessionId": { "type": "keyword" },
                "question":  { "type": "text" },
                "answer":    { "type": "text" },
                "steps":     { "type": "integer" },
                "tools":     { "type": "keyword" },
                "success":   { "type": "boolean" },
                "errorCode": { "type": "keyword" },
                "createdAt": { "type": "date", "format": "strict_date_optional_time||epoch_millis" }
              }
            }
            """;

    private final RestHighLevelClient client;
    private final AiHistoryProperties properties;

    /** 建索引只做一次，失败后下次请求会重试。 */
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    /**
     * @param client     ES 高级客户端，由 zeroai-data-elastic 提供
     * @param properties 历史配置（索引名）
     */
    public EsAiChatHistoryStore(RestHighLevelClient client, AiHistoryProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void save(AiChatExchange exchange) {
        if (exchange == null) {
            return;
        }
        try {
            ensureIndex();
            IndexRequest request = new IndexRequest(properties.getIndex())
                    .id(exchange.id())
                    .source(toJson(exchange), XContentType.JSON);
            // 立即刷新：用户答完刷新页面就应该看到这条记录
            request.setRefreshPolicy(org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE);
            client.index(request, RequestOptions.DEFAULT);
        } catch (Exception e) {
            log.warn("AI 历史写入 ES 失败，已忽略：userId={} index={} error={}",
                    exchange.userId(), properties.getIndex(), e.getMessage());
        }
    }

    @Override
    public List<AiChatExchange> recent(Long userId, int size) {
        if (userId == null || size <= 0) {
            return List.of();
        }
        List<AiChatExchange> list = new ArrayList<>(size);
        try {
            if (!indexExists()) {
                return list;
            }
            SearchSourceBuilder source = new SearchSourceBuilder()
                    .query(QueryBuilders.termQuery(FIELD_USER_ID, userId))
                    .sort(FIELD_CREATED_AT, SortOrder.DESC)
                    .size(size)
                    .timeout(TimeValue.timeValueSeconds(3));
            SearchRequest request = new SearchRequest(properties.getIndex()).source(source);
            SearchResponse response = client.search(request, RequestOptions.DEFAULT);
            for (SearchHit hit : response.getHits().getHits()) {
                AiChatExchange exchange = fromSource(hit.getId(), hit.getSourceAsMap());
                if (exchange != null) {
                    list.add(exchange);
                }
            }
        } catch (Exception e) {
            log.warn("查询 AI 历史失败，按无历史返回：userId={} error={}", userId, e.getMessage());
            return List.of();
        }
        return list;
    }

    @Override
    public int clear(Long userId) {
        if (userId == null) {
            return 0;
        }
        try {
            if (!indexExists()) {
                return 0;
            }
            DeleteByQueryRequest request = new DeleteByQueryRequest(properties.getIndex());
            request.setQuery(QueryBuilders.termQuery(FIELD_USER_ID, userId));
            request.setRefresh(true);
            BulkByScrollResponse response = client.deleteByQuery(request, RequestOptions.DEFAULT);
            return (int) response.getDeleted();
        } catch (Exception e) {
            log.warn("清空 AI 历史失败：userId={} error={}", userId, e.getMessage());
            return 0;
        }
    }

    /**
     * 判断索引是否已存在。不存在时直接返回空，不去建索引——
     * 只读请求不应该产生写副作用。
     *
     * @return 索引存在返回 true
     */
    private boolean indexExists() throws Exception {
        if (indexReady.get()) {
            return true;
        }
        if (client.indices().exists(new GetIndexRequest(properties.getIndex()), RequestOptions.DEFAULT)) {
            indexReady.set(true);
            return true;
        }
        return false;
    }

    /**
     * 确保索引存在。并发建索引只会有一个成功，其余按「已存在」处理。
     */
    private void ensureIndex() throws Exception {
        if (indexReady.get()) {
            return;
        }
        synchronized (this) {
            if (indexReady.get()) {
                return;
            }
            String index = properties.getIndex();
            if (!client.indices().exists(new GetIndexRequest(index), RequestOptions.DEFAULT)) {
                CreateIndexRequest create = new CreateIndexRequest(index);
                // 单节点开发环境：1 分片 0 副本，避免副本无法分配让集群一直 yellow
                create.settings(Settings.builder()
                        .put("number_of_shards", 1)
                        .put("number_of_replicas", 0));
                create.mapping(INDEX_MAPPING, XContentType.JSON);
                try {
                    client.indices().create(create, RequestOptions.DEFAULT);
                    log.info("已创建 AI 历史索引 index={}", index);
                } catch (ResourceAlreadyExistsException e) {
                    log.debug("AI 历史索引已被并发创建 index={}", index);
                }
            }
            indexReady.set(true);
        }
    }

    /**
     * 存档对象转 JSON。字段名与 {@link #INDEX_MAPPING} 一一对应。
     *
     * @param exchange 存档记录
     * @return JSON 字符串
     */
    private static String toJson(AiChatExchange exchange) {
        JSONObject doc = new JSONObject();
        doc.put(FIELD_USER_ID, exchange.userId());
        doc.put("sessionId", exchange.sessionId());
        doc.put("question", exchange.question());
        doc.put("answer", exchange.answer());
        doc.put("steps", exchange.steps());
        JSONArray tools = new JSONArray();
        if (exchange.tools() != null) {
            exchange.tools().forEach(tools::add);
        }
        doc.put("tools", tools);
        doc.put("success", exchange.success());
        doc.put("errorCode", exchange.errorCode());
        doc.put(FIELD_CREATED_AT, exchange.createdAt());
        return doc.toJSONString();
    }

    /**
     * ES 文档转存档对象。缺字段按空值处理，不因一条脏数据让整页历史报错。
     *
     * @param id     文档 ID
     * @param source 文档字段
     * @return 存档对象；无法解析时返回 null
     */
    @SuppressWarnings("unchecked")
    private static AiChatExchange fromSource(String id, Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Object rawUserId = source.get(FIELD_USER_ID);
        Long userId = rawUserId instanceof Number number ? number.longValue() : null;
        List<String> tools = new ArrayList<>();
        if (source.get("tools") instanceof List<?> rawTools) {
            rawTools.stream().filter(item -> item instanceof String)
                    .map(item -> (String) item).forEach(tools::add);
        }
        return new AiChatExchange(
                id,
                userId,
                asString(source.get("sessionId")),
                asString(source.get("question")),
                asString(source.get("answer")),
                source.get("steps") instanceof Number steps ? steps.intValue() : 0,
                tools,
                Boolean.TRUE.equals(source.get("success")),
                asString(source.get("errorCode")),
                asString(source.get(FIELD_CREATED_AT)));
    }

    /**
     * 宽松转字符串：ES 的 date 字段在 source 里是字符串，但不同版本可能给出其他类型。
     *
     * @param value 原始值
     * @return 字符串，null 保持 null
     */
    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof String text ? text : String.valueOf(value);
    }
}
