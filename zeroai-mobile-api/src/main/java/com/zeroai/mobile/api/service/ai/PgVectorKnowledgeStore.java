package com.zeroai.mobile.api.service.ai;

import com.zeroai.ai.knowledge.KnowledgeChunker;
import com.zeroai.ai.knowledge.KnowledgeDocument;
import com.zeroai.ai.knowledge.KnowledgeEmbedder;
import com.zeroai.ai.knowledge.KnowledgeEmbeddingException;
import com.zeroai.ai.knowledge.KnowledgeHit;
import com.zeroai.ai.knowledge.KnowledgeCatalog;
import com.zeroai.ai.knowledge.KnowledgeProperties;
import com.zeroai.ai.knowledge.VectorKnowledgePort;
import com.zeroai.mobile.api.config.PgVectorProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库的 PGVector 实现：启动同步 + 余弦检索。
 * <p>
 * <b>为什么放在入口模块而不是 starter</b>：与 {@code EsKnowledgeStore} 同一条边界——
 * {@code zeroai-ai-starter} 不引入任何数据库依赖，存储实现属于部署侧选择。
 * <p>
 * <b>为什么不用 Spring AI 的 PgVectorStore</b>：它要求一个 {@code EmbeddingModel} Bean，
 * 而本项目的向量化已经由 {@code KnowledgeEmbedder}（OpenAI 兼容 HTTP 客户端）承担；
 * 再引入一套 embedding 抽象就会出现两份维度、超时、批量配置，且互相不知道对方改了。
 * 这里的 SQL 只有建表、upsert、余弦查询三类，直连 JDBC 反而更容易解释和排障。
 * <p>
 * <b>连接池为什么不注册成 Bean</b>：{@code DataSourceAutoConfiguration} 的条件是
 * 容器里没有 {@code DataSource}。一旦这个池子变成 Bean，Spring Boot 会放弃装配主数据源，
 * MyBatis 有概率绑定到知识库库上。池子由本类私有持有并自行关闭，是从根上避免这个事故。
 * <p>
 * <b>失败语义</b>：同步失败只记 ERROR，不阻断应用启动；检索失败抛
 * {@link IllegalStateException}，由 {@code KnowledgeService} 转成 {@code UNAVAILABLE}——
 * 「知识库连不上」和「知识库里没有这条政策」必须让模型能区分。
 */
@Component
@ConditionalOnProperty(prefix = "zeroai.ai.knowledge.vector", name = "store", havingValue = "pgvector")
@ConditionalOnExpression("${zeroai.ai.enabled:false} && ${zeroai.ai.knowledge.enabled:true}")
public class PgVectorKnowledgeStore implements VectorKnowledgePort, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PgVectorKnowledgeStore.class);

    /** 表名白名单：只允许字母、数字、下划线，避免配置项变成 SQL 注入点。 */
    private static final String TABLE_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";

    private final KnowledgeProperties properties;
    private final PgVectorProperties pg;
    private final KnowledgeEmbedder embedder;
    private final String table;
    private final JdbcTemplate jdbc;
    private final HikariDataSource dataSource;

    /** 表结构是否已校验通过。volatile：只在首次检索/同步时校验一次。 */
    private volatile boolean schemaReady;

    /**
     * @param embedderProvider 向量化端口，可为空（未配置 embedding 时检索直接不可用）
     * @param properties       知识库配置
     * @param pg               PG 连接配置
     */
    public PgVectorKnowledgeStore(ObjectProvider<KnowledgeEmbedder> embedderProvider,
                                  KnowledgeProperties properties,
                                  PgVectorProperties pg) {
        this.properties = properties;
        this.pg = pg;
        this.embedder = embedderProvider.getIfAvailable();
        this.table = requireValidTable(pg.getTable());
        this.dataSource = buildDataSource(pg);
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(Math.max(1, pg.getQueryTimeoutSeconds()));
    }

    /**
     * 构建私有连接池。
     * <p>
     * {@code initializationFailTimeout = -1}：启动时不试连。知识库是可选能力，
     * PG 没起不应该让商城起不来；第一次同步/检索时再暴露失败。
     *
     * @param config PG 配置
     * @return 连接池
     */
    private static HikariDataSource buildDataSource(PgVectorProperties config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("pgvector-knowledge");
        hikari.setJdbcUrl(config.getUrl());
        hikari.setUsername(config.getUsername());
        hikari.setPassword(config.getPassword());
        hikari.setMaximumPoolSize(Math.max(1, config.getPoolSize()));
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(config.getConnectionTimeoutMs());
        hikari.setInitializationFailTimeout(-1L);
        return new HikariDataSource(hikari);
    }

    /**
     * 启动时同步索引。
     * <p>
     * 纯 BM25 模式或关闭了 {@code sync-on-startup} 时直接跳过：向量模式才需要 embedding 开销。
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!properties.resolveMode().needsEmbedding()) {
            log.debug("当前检索模式为 {}，跳过 PGVector 同步", properties.resolveMode().code());
            return;
        }
        if (!properties.isSyncOnStartup()) {
            log.info("PGVector 启动同步已关闭（sync-on-startup=false）");
            return;
        }
        long started = System.currentTimeMillis();
        try {
            Report report = sync();
            log.info("PGVector 同步完成 documents={} chunks={} written={} skipped={} model={} costMs={}",
                    report.documents(), report.chunks(), report.written(), report.skipped(),
                    embedder == null ? "none" : embedder.model(), System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.error("PGVector 同步失败，向量检索将在修复前不可用：{}", e.getMessage());
        }
    }

    /**
     * 全量同步：按内容哈希做增量，只重算变化的块。
     * <p>
     * 同步是幂等的，可以在每次启动执行。单篇失败只跳过该篇并记 ERROR，
     * 不整批回滚——知识库是派生数据，半新半旧好过整体不可用（旧块仍然可答）。
     *
     * @return 同步统计
     */
    public Report sync() {
        if (embedder == null) {
            throw new KnowledgeEmbeddingException("embedding 未配置，无法同步 PGVector 索引");
        }
        ensureSchema();
        KnowledgeCatalog.LoadResult catalog = KnowledgeCatalog.load(properties.getLocation());
        String visibility = pg.getVisibility();
        List<KnowledgeDocument> documents = catalog.documents().stream()
                .filter(document -> document.visibility().code().equalsIgnoreCase(visibility))
                .toList();
        if (documents.isEmpty()) {
            // 目录为空多半是打包/配置错误。此时清空索引等于让知识库静默消失，必须拒绝。
            throw new IllegalStateException("知识目录为空，拒绝清空 PGVector 索引：location="
                    + properties.getLocation() + " visibility=" + visibility);
        }

        List<String> currentIds = new ArrayList<>(documents.size());
        int totalChunks = 0;
        int written = 0;
        int skipped = 0;
        for (KnowledgeDocument document : documents) {
            currentIds.add(document.sourceId());
            try {
                SyncStat stat = syncDocument(document);
                totalChunks += stat.chunks();
                written += stat.written();
                skipped += stat.skipped();
            } catch (RuntimeException e) {
                totalChunks += 0;
                log.error("文档同步失败，已跳过 sourceId={} error={}", document.sourceId(), e.getMessage());
            }
        }
        removeOrphans(currentIds);
        return new Report(documents.size(), totalChunks, written, skipped);
    }

    /**
     * 同步单篇文档：计算块哈希 → 只向量化变化的块 → 单连接事务内替换。
     *
     * @param document 文档
     * @return 该篇统计
     */
    private SyncStat syncDocument(KnowledgeDocument document) {
        List<KnowledgeChunker.Chunk> chunks = KnowledgeChunker.split(document,
                properties.getVector().getChunkSize(),
                properties.getVector().getMinChunkChars(),
                properties.getVector().isPrefixTitle());
        if (chunks.isEmpty()) {
            return new SyncStat(0, 0, 0);
        }

        Map<Integer, String> existing = new HashMap<>();
        jdbc.query("SELECT chunk_index, content_hash FROM " + table + " WHERE source_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) resultSet ->
                        existing.put(resultSet.getInt(1), resultSet.getString(2)),
                document.sourceId());

        List<Integer> changedIndexes = new ArrayList<>();
        List<String> changedTexts = new ArrayList<>();
        List<String> hashes = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            String text = chunks.get(index).text();
            String hash = sha256(text);
            hashes.add(hash);
            if (!hash.equals(existing.get(index))) {
                changedIndexes.add(index);
                changedTexts.add(text);
            }
        }

        List<float[]> vectors = changedTexts.isEmpty() ? List.of() : embedder.embedAll(changedTexts);
        if (vectors.size() != changedTexts.size()) {
            throw new IllegalStateException("向量数量与文本数量不一致：texts=" + changedTexts.size()
                    + " vectors=" + vectors.size());
        }

        // 单篇文档的替换放在一个连接里手动提交：不引入第二个 PlatformTransactionManager，
        // 避免与 MyBatis 的主事务管理器争 @Primary。
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                // 文档变短时，尾部旧块必须删掉，否则会召回已经不存在的条款
                try (var ps = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE source_id = ? AND chunk_index >= ?")) {
                    ps.setString(1, document.sourceId());
                    ps.setInt(2, chunks.size());
                    ps.executeUpdate();
                }
                if (!changedIndexes.isEmpty()) {
                    try (var ps = connection.prepareStatement("INSERT INTO " + table
                            + " (source_id, chunk_index, title, section, category, tags, visibility,"
                            + " version, updated_at, content, content_hash, embedding, embedding_model)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?)"
                            + " ON CONFLICT (source_id, chunk_index) DO UPDATE SET"
                            + " title = EXCLUDED.title, section = EXCLUDED.section,"
                            + " category = EXCLUDED.category, tags = EXCLUDED.tags,"
                            + " visibility = EXCLUDED.visibility, version = EXCLUDED.version,"
                            + " updated_at = EXCLUDED.updated_at, content = EXCLUDED.content,"
                            + " content_hash = EXCLUDED.content_hash, embedding = EXCLUDED.embedding,"
                            + " embedding_model = EXCLUDED.embedding_model, updated_ts = now()")) {
                        for (int cursor = 0; cursor < changedIndexes.size(); cursor++) {
                            int index = changedIndexes.get(cursor);
                            KnowledgeChunker.Chunk chunk = chunks.get(index);
                            ps.setString(1, document.sourceId());
                            ps.setInt(2, index);
                            ps.setString(3, document.title());
                            ps.setString(4, chunk.section());
                            ps.setString(5, document.category());
                            ps.setArray(6, connection.createArrayOf("text", document.tags().toArray()));
                            ps.setString(7, document.visibility().code());
                            ps.setInt(8, document.version());
                            ps.setDate(9, toSqlDate(document.updatedAt()));
                            ps.setString(10, chunk.text());
                            ps.setString(11, hashes.get(index));
                            ps.setString(12, toVectorLiteral(vectors.get(cursor)));
                            ps.setString(13, embedder.model());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                connection.commit();
                return null;
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        });

        return new SyncStat(chunks.size(), changedIndexes.size(), chunks.size() - changedIndexes.size());
    }

    /**
     * 删除本次加载中不再存在的文档块。
     *
     * @param currentIds 本次加载到的 sourceId
     */
    private void removeOrphans(List<String> currentIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(currentIds.size(), "?"));
        int removed = jdbc.update("DELETE FROM " + table + " WHERE source_id NOT IN (" + placeholders + ")",
                currentIds.toArray());
        if (removed > 0) {
            log.info("PGVector 清理孤儿知识块 count={}", removed);
        }
    }

    /**
     * 检索知识片段。
     *
     * @param query 检索问题
     * @param limit 条数上限
     * @return 命中列表
     * @throws IllegalStateException 索引不可用或查询失败
     */
    @Override
    public List<KnowledgeHit> search(String query, int limit) {
        if (embedder == null) {
            throw new IllegalStateException("embedding 未配置，PGVector 检索不可用");
        }
        ensureSchema();
        List<KnowledgeHit> hits = new ArrayList<>(limit);
        try {
            String vector = toVectorLiteral(embedder.embed(query));
            String sql = "SELECT source_id, title, category, version, updated_at, content,"
                    + " 1 - (embedding <=> ?::vector) AS score"
                    + " FROM " + table
                    + " WHERE visibility = ? AND 1 - (embedding <=> ?::vector) >= ?"
                    + " ORDER BY embedding <=> ?::vector LIMIT ?";
            jdbc.query(sql, (org.springframework.jdbc.core.RowCallbackHandler) resultSet ->
                    hits.add(new KnowledgeHit(
                            resultSet.getString("source_id"),
                            resultSet.getString("title"),
                            resultSet.getString("category"),
                            resultSet.getInt("version"),
                            resultSet.getDate("updated_at").toLocalDate().toString(),
                            resultSet.getString("content"),
                            resultSet.getDouble("score"))),
                    vector, pg.getVisibility(), vector, properties.getVector().getMinScore(), vector, limit);
        } catch (RuntimeException e) {
            throw new IllegalStateException("PGVector 检索失败：" + e.getMessage(), e);
        }
        return hits;
    }

    /**
     * 校验表结构与维度。
     * <p>
     * 维度配错是这类系统最隐蔽的故障：检索不报错，只是永远返回不相关的结果。
     * 所以这里把「表定义的 vector(N) 必须等于 embedding.dimension」做成启动硬校验。
     */
    private void ensureSchema() {
        if (schemaReady) {
            return;
        }
        synchronized (this) {
            if (schemaReady) {
                return;
            }
            if (embedder == null) {
                throw new IllegalStateException("embedding 未配置，无法校验 PGVector 表结构");
            }
            String actual;
            try {
                // 用 queryForList 而不是 queryForObject：同名表可能存在于多个 schema，
                // 取第一条即可，避免 IncorrectResultSize 把「表存在」误报成「表不可用」
                List<String> types = jdbc.queryForList(
                        "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a"
                                + " JOIN pg_class c ON c.oid = a.attrelid"
                                + " WHERE c.relname = ? AND a.attname = 'embedding'"
                                + " AND a.attnum > 0 AND NOT a.attisdropped"
                                + " ORDER BY c.oid DESC",
                        String.class, table);
                if (types.isEmpty()) {
                    throw new IllegalStateException("PGVector 表不存在：table=" + table);
                }
                actual = types.get(0);
            } catch (IllegalStateException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new IllegalStateException("PGVector 表不存在或无法访问：table=" + table
                        + "，请先执行 pgvector/init/01-schema.sql", e);
            }
            String expected = "vector(" + embedder.dimension() + ")";
            if (!expected.equalsIgnoreCase(actual)) {
                throw new IllegalStateException("PGVector 表维度与 embedding 不一致：表=" + actual
                        + " 配置=" + expected + "，请重建表或改回匹配的模型");
            }
            schemaReady = true;
            log.info("PGVector 表校验通过 table={} type={} model={}", table, actual, embedder.model());
        }
    }

    /**
     * 把 frontmatter 的日期转成 SQL Date。
     * <p>
     * 只取前 10 位：知识文档约定写 {@code yyyy-MM-dd}，但历史文档可能是完整 ISO 时间戳；
     * 因为格式问题整篇被跳过不值得。
     *
     * @param value 日期字符串
     * @return SQL 日期
     */
    private static Date toSqlDate(String value) {
        String day = value.length() >= 10 ? value.substring(0, 10) : value;
        return Date.valueOf(day);
    }

    /**
     * 校验并返回表名。
     *
     * @param raw 配置值
     * @return 合法表名
     */
    private static String requireValidTable(String raw) {
        if (raw == null || !raw.matches(TABLE_PATTERN)) {
            throw new IllegalArgumentException("zeroai.ai.knowledge.pg.table 非法：" + raw);
        }
        return raw;
    }

    /**
     * 向量转 pgvector 文本字面量。
     * <p>
     * pgjdbc 不认识 {@code vector} 类型，必须传字符串再 {@code ?::vector} 显式转换；
     * 引入 pgvector-java 只为省这一行不值得。
     *
     * @param vector 向量
     * @return {@code [0.1,0.2,...]}
     */
    private static String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 8).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(Float.toString(vector[index]));
        }
        return builder.append(']').toString();
    }

    /**
     * 计算块内容哈希，用于增量同步。
     *
     * @param text 块文本
     * @return 十六进制 sha256
     */
    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(Character.forDigit((item >> 4) & 0xF, 16));
                builder.append(Character.forDigit(item & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /**
     * 关闭私有连接池。
     */
    @PreDestroy
    public void close() {
        dataSource.close();
    }

    /**
     * 单篇文档的同步统计。
     *
     * @param chunks  该篇切出的块数
     * @param written 重新向量化并写入的块数
     * @param skipped 命中哈希、跳过的块数
     */
    private record SyncStat(int chunks, int written, int skipped) {
    }

    /**
     * 整次同步的统计。
     *
     * @param documents 参与同步的文档数
     * @param chunks    切块总数
     * @param written   实际写入（重新向量化）的块数
     * @param skipped   哈希命中、跳过的块数
     */
    public record Report(int documents, int chunks, int written, int skipped) {
    }
}
