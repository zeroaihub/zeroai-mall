package com.zeroai.mobile.api.service.ai;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.zeroai.ai.knowledge.Bm25KnowledgePort;
import com.zeroai.ai.knowledge.KnowledgeCatalog;
import com.zeroai.ai.knowledge.KnowledgeDocument;
import com.zeroai.ai.knowledge.KnowledgeHit;
import com.zeroai.ai.knowledge.KnowledgeProperties;
import com.zeroai.ai.knowledge.KnowledgeQueryNormalizer;
import com.zeroai.ai.knowledge.KnowledgeVisibility;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库的 Elasticsearch 实现：索引同步 + 检索。
 * <p>
 * <b>为什么复用 ES 而不是引入向量库</b>：知识语料是十几篇人工编写的政策文档，
 * 提问方式集中在固定的业务词汇上（退款、运费、优惠券），BM25 + 中文分词已经够用；
 * 引入 PGVector 要新增一个中间件、一套 embedding 调用、一条失败链路，
 * 换来的语义召回收益在当前语料规模上无法验证。等命中率数据证明 BM25 不够时再换，
 * 换的时候只需要替换本类的实现，上层 {@code KnowledgeService} 与工具不动。
 * <p>
 * <b>为什么不把知识库和商品索引合并</b>：商品索引是实时数据（价格、库存随时变），
 * 知识索引是低频变更的规则文本。合并会让「商品同步任务」有权限改知识内容，
 * 也会让一次商品全量重建把知识索引一起冲掉。
 * <p>
 * <b>同步语义</b>：文档 ID 固定为 {@code sourceId}，同一篇文档内容更新即整篇覆盖；
 * 本次加载中不存在的 sourceId 会被删除。也就是说索引完全跟随 jar 内的
 * {@code knowledge/*.md}，任何绕过代码直接改索引的行为都会在下次启动时被纠正。
 */
@Slf4j
@Component
@ConditionalOnExpression("${zeroai.ai.enabled:false} && ${zeroai.ai.knowledge.enabled:true}")
public class EsKnowledgeStore implements Bm25KnowledgePort, ApplicationRunner {

    /** 可见范围字段，所有检索都必须带上 public 过滤。 */
    private static final String FIELD_VISIBILITY = "visibility";

    /** 正文/标题/标签字段名。 */
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_TAGS = "tags";

    /** 单次检索超时。工具层超时是 3s，这里留出余量，避免检索把整轮回答拖死。 */
    private static final TimeValue QUERY_TIMEOUT = TimeValue.timeValueMillis(2000);

    /** 片段高亮窗口大小。 */
    private static final int FRAGMENT_SIZE = 120;

    /**
     * 索引设置：分片数 + 中文停用词分析器。
     * <p>
     * 为什么要自定义分析器：ik 会把「是」「的」「怎么」「可以」都切成有效词元，
     * 于是「订单金额是怎么算出来的」这种问句里，任何文档都能靠这几个虚词凑够
     * {@code minimum_should_match} 比例，出现「命中率很高但答非所问」的假阳性。
     * 停用词过滤后，这句只剩「订单 / 金额 / 算出来」，匹配不到就该返回查不到。
     * <p>
     * 停用词表只收虚词与疑问词，<b>不</b>收业务词：把「退款」「运费」停掉等于自废召回。
     */
    private static final String INDEX_SETTINGS = """
            {
              "number_of_shards": 1,
              "number_of_replicas": 0,
              "analysis": {
                "filter": {
                  "zh_stop": {
                    "type": "stop",
                    "stopwords": ["是", "的", "了", "吗", "呢", "吧", "啊", "呀", "嘛",
                                  "怎么", "怎样", "怎么样", "什么", "哪些", "哪个", "如何",
                                  "可以", "能否", "能", "会不会", "有没有", "多少", "请问",
                                  "一下", "你们", "我们", "我", "你"]
                  }
                },
                "analyzer": {
                  "kb_index": { "type": "custom", "tokenizer": "ik_max_word", "filter": ["lowercase", "zh_stop"] },
                  "kb_search": { "type": "custom", "tokenizer": "ik_smart", "filter": ["lowercase", "zh_stop"] }
                }
              }
            }
            """;

    /**
     * 索引结构。
     * <p>
     * 标题与正文用停用词版 ik 分词；{@code tags} 也分词，并保留 keyword 子字段：
     * 标签是人工维护的「用户会怎么说」的同义词表（订单状态、运费怎么算、券用不了），
     * 分词后参与打分才能把「问法」和「文档主题」连起来；
     * keyword 子字段留给后续按标签精确过滤。
     * <p>
     * 注意：修改本结构后必须删掉旧索引（{@code DELETE /zeroai_ai_kb}）再重启，
     * ES 不支持直接改已有字段的类型。知识索引是纯派生数据，重建不会丢任何事实来源。
     */
    private static final String INDEX_MAPPING = """
            {
              "properties": {
                "sourceId":   { "type": "keyword" },
                "title":      { "type": "text", "analyzer": "kb_index", "search_analyzer": "kb_search" },
                "category":   { "type": "keyword" },
                "version":    { "type": "integer" },
                "updatedAt":  { "type": "date", "format": "strict_date_optional_time||yyyy-MM-dd||epoch_millis" },
                "visibility": { "type": "keyword" },
                "tags":       { "type": "text", "analyzer": "kb_index", "search_analyzer": "kb_search",
                                "fields": { "kw": { "type": "keyword" } } },
                "content":    { "type": "text", "analyzer": "kb_index", "search_analyzer": "kb_search" }
              }
            }
            """;

    private final RestHighLevelClient client;
    private final KnowledgeProperties properties;

    /** 建索引只做一次；失败后下次调用会重试。 */
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    /**
     * @param client     ES 高级客户端，由 zeroai-data-elastic 提供
     * @param properties 知识库配置（索引名、文档目录、检索条数）
     */
    public EsKnowledgeStore(RestHighLevelClient client, KnowledgeProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 应用启动后同步知识索引。
     * <p>
     * 同步失败只记 ERROR，不阻断启动：知识库是可选能力，ES 抖动不应该让商城起不来。
     * 同步失败时检索仍会走旧索引（如果存在），最坏情况是政策口径滞后，
     * 这比「整个服务不可用」代价小得多。
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isSyncOnStartup()) {
            log.info("知识索引启动同步已关闭（sync-on-startup=false），跳过");
            return;
        }
        try {
            SyncReport report = sync();
            log.info("知识索引同步完成 index={} documents={} indexed={} deleted={} rejected={}",
                    properties.getIndex(), report.documents(), report.indexed(), report.deleted(),
                    report.rejected());
        } catch (Exception e) {
            log.error("知识索引同步失败，检索将退化为「查询不到」：index={} error={}",
                    properties.getIndex(), e.getMessage(), e);
        }
    }

    /**
     * 全量同步：写入全部公开文档，删除本次加载中不存在的文档。
     *
     * @return 同步统计
     * @throws Exception ES 不可用
     */
    public SyncReport sync() throws Exception {
        ensureIndex();
        KnowledgeCatalog.LoadResult catalog = KnowledgeCatalog.load(properties.getLocation());
        List<KnowledgeDocument> documents = catalog.publicDocuments();

        Set<String> currentIds = new LinkedHashSet<>();
        BulkRequest bulk = new BulkRequest();
        for (KnowledgeDocument document : documents) {
            currentIds.add(document.sourceId());
            bulk.add(new IndexRequest(properties.getIndex())
                    .id(document.sourceId())
                    .source(toJson(document), XContentType.JSON));
        }
        int indexed = 0;
        if (bulk.numberOfActions() > 0) {
            // bulk 为空时提交会抛 IllegalArgumentException，必须先判空
            BulkResponse response = client.bulk(bulk.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE),
                    RequestOptions.DEFAULT);
            if (response.hasFailures()) {
                log.error("知识索引部分写入失败：{}", response.buildFailureMessage());
            }
            indexed = documents.size();
        }

        int deleted = deleteStale(currentIds);
        return new SyncReport(documents.size(), indexed, deleted, catalog.rejected().size());
    }

    /**
     * 删除索引里已不在文档集合中的记录。
     *
     * @param currentIds 当前文档 ID 集合
     * @return 删除条数
     */
    private int deleteStale(Set<String> currentIds) throws Exception {
        SearchSourceBuilder source = new SearchSourceBuilder()
                .query(QueryBuilders.matchAllQuery())
                // 知识语料是十几篇量级，一次取完比滚动查询简单可靠
                .size(1000)
                .fetchSource(new String[]{"sourceId"}, null);
        SearchResponse response = client.search(
                new SearchRequest(properties.getIndex()).source(source), RequestOptions.DEFAULT);

        BulkRequest bulk = new BulkRequest();
        for (SearchHit hit : response.getHits().getHits()) {
            if (!currentIds.contains(hit.getId())) {
                bulk.add(new org.elasticsearch.action.delete.DeleteRequest(properties.getIndex(), hit.getId()));
            }
        }
        if (bulk.numberOfActions() == 0) {
            return 0;
        }
        client.bulk(bulk.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE), RequestOptions.DEFAULT);
        log.warn("知识索引清理了 {} 篇已下线文档", bulk.numberOfActions());
        return bulk.numberOfActions();
    }

    /**
     * 检索知识片段。
     * <p>
     * 查询形态：{@code title^3 + tags^2 + content} 的 best_fields 匹配，
     * 并要求至少命中 60% 的查询词——这是「宁可查不到，也不要答错」的取舍：
     * 政策类回答一旦用错文档，比明确说不知道更糟。
     *
     * @param query 检索问题
     * @param limit 条数上限
     * @return 命中列表；检索故障抛 {@link IllegalStateException} 由门面转为「不可用」
     */
    @Override
    public List<KnowledgeHit> search(String rawQuery, int limit) {
        // 倒排索引专用的清洗：剔除疑问词与口头语，让 minimum_should_match 只统计业务词。
        // 这一步不能放到门面里做——向量检索恰恰需要保留这些词来表达语义。
        String query = KnowledgeQueryNormalizer.stripNoise(rawQuery, properties.getMaxQueryChars());
        if (query.isEmpty()) {
            return List.of();
        }
        try {
            if (!indexExists()) {
                // 索引还没建起来（首次启动同步失败等）：明确表达「查不到」，不当成有空结果
                return List.of();
            }
            MultiMatchQueryBuilder match = QueryBuilders.multiMatchQuery(query)
                    // 标题最权威，标签是人工维护的问法同义词，正文兜底细节
                    .field(FIELD_TITLE, 5.0f)
                    .field(FIELD_TAGS, 3.0f)
                    .field(FIELD_CONTENT, 1.0f)
                    .type(MultiMatchQueryBuilder.Type.BEST_FIELDS)
                    // 覆盖率要求可配置：调阈值是检索调优的主要手段，不该改代码
                    .minimumShouldMatch(properties.getMinShouldMatch());
            BoolQueryBuilder bool = QueryBuilders.boolQuery()
                    .must(match)
                    .filter(QueryBuilders.termQuery(FIELD_VISIBILITY, KnowledgeVisibility.PUBLIC.code()));

            HighlightBuilder highlight = new HighlightBuilder()
                    .field(FIELD_CONTENT)
                    .preTags("")
                    .postTags("")
                    .fragmentSize(FRAGMENT_SIZE)
                    .numOfFragments(2)
                    .requireFieldMatch(false);

            SearchSourceBuilder source = new SearchSourceBuilder()
                    .query(bool)
                    .size(limit)
                    // 得分下限在检索层就生效，避免低分噪声挤占有限的返回条数
                    .minScore((float) properties.getMinScore())
                    .timeout(QUERY_TIMEOUT)
                    .highlighter(highlight);
            SearchResponse response = client.search(
                    new SearchRequest(properties.getIndex()).source(source), RequestOptions.DEFAULT);

            List<KnowledgeHit> hits = new ArrayList<>(limit);
            for (SearchHit hit : response.getHits().getHits()) {
                Map<String, Object> doc = hit.getSourceAsMap();
                String snippet = joinFragments(hit.getHighlightFields().get(FIELD_CONTENT));
                if (snippet.isEmpty()) {
                    // 命中发生在标题或标签上时没有正文高亮，退回正文前段
                    snippet = asString(doc.get(FIELD_CONTENT));
                }
                hits.add(new KnowledgeHit(
                        asString(doc.get("sourceId")),
                        asString(doc.get(FIELD_TITLE)),
                        asString(doc.get("category")),
                        doc.get("version") instanceof Number version ? version.intValue() : 1,
                        asString(doc.get("updatedAt")),
                        snippet,
                        hit.getScore()));
            }
            return hits;
        } catch (Exception e) {
            throw new IllegalStateException("知识检索失败：" + e.getMessage(), e);
        }
    }

    /**
     * 拼接高亮片段。ES 会把命中词按 fragment 切开，拼回去才能保持语义连贯。
     *
     * @param field 高亮字段，可为 null
     * @return 拼接后的文本，无高亮时返回空串
     */
    private static String joinFragments(HighlightField field) {
        if (field == null || field.fragments() == null || field.fragments().length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var fragment : field.fragments()) {
            sb.append(fragment.string());
        }
        return sb.toString().trim();
    }

    /**
     * 判断索引是否存在。只读路径不建索引，避免查询请求产生写副作用。
     *
     * @return 存在返回 true
     * @throws Exception ES 不可用
     */
    private boolean indexExists() throws Exception {
        if (indexReady.get()) {
            return true;
        }
        boolean exists = client.indices().exists(new GetIndexRequest(properties.getIndex()), RequestOptions.DEFAULT);
        if (exists) {
            indexReady.set(true);
        }
        return exists;
    }

    /**
     * 确保索引存在。并发创建只有一个成功，其余按「已存在」处理。
     *
     * @throws Exception ES 不可用
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
                // 单节点开发环境：1 分片 0 副本，避免副本无法分配让集群一直 yellow；
                // analysis 段必须随 settings 一起提交，mapping 里才能引用 kb_index / kb_search
                create.settings(INDEX_SETTINGS, XContentType.JSON);
                create.mapping(INDEX_MAPPING, XContentType.JSON);
                try {
                    client.indices().create(create, RequestOptions.DEFAULT);
                    log.info("已创建知识索引 index={}", index);
                } catch (ResourceAlreadyExistsException e) {
                    log.debug("知识索引已被并发创建 index={}", index);
                }
            }
            indexReady.set(true);
        }
    }

    /**
     * 文档转索引 JSON。字段名与 {@link #INDEX_MAPPING} 一一对应。
     *
     * @param document 知识文档
     * @return JSON 字符串
     */
    private static String toJson(KnowledgeDocument document) {
        JSONObject json = new JSONObject();
        json.put("sourceId", document.sourceId());
        json.put(FIELD_TITLE, document.title());
        json.put("category", document.category());
        json.put("version", document.version());
        json.put("updatedAt", document.updatedAt());
        json.put(FIELD_VISIBILITY, document.visibility().code());
        JSONArray tags = new JSONArray();
        document.tags().forEach(tags::add);
        json.put(FIELD_TAGS, tags);
        json.put(FIELD_CONTENT, document.content());
        return json.toJSONString();
    }

    /**
     * 宽松转字符串：ES 的日期字段在 source 里可能是字符串，也可能是 epoch 数值。
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

    /**
     * 同步统计。
     *
     * @param documents 加载到的公开文档数
     * @param indexed   实际写入数
     * @param deleted   清理的过期文档数
     * @param rejected  未通过校验的文档数
     */
    public record SyncReport(int documents, int indexed, int deleted, int rejected) {
    }
}
