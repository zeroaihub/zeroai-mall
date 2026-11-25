package com.zeroai.mobile.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PGVector 知识库连接配置。
 * <p>
 * 只在 {@code zeroai.ai.knowledge.vector.store=pgvector} 时被使用。
 * 与商城主数据源（MySQL）完全独立：连接池由
 * {@code PgVectorKnowledgeStore} 私有持有，<b>不注册成 DataSource Bean</b>——
 * Spring Boot 的 {@code DataSourceAutoConfiguration} 条件是「容器里没有 DataSource」，
 * 多出一个 Bean 会让 MyBatis 有可能绑定到知识库库上，属于灾难级故障。
 * <p>
 * 密码只从环境变量注入，不写进 application.yml（见 {@code .env.example}）。
 */
@Component
@ConfigurationProperties(prefix = "zeroai.ai.knowledge.pg")
public class PgVectorProperties {

    /**
     * JDBC 连接串。
     */
    private String url = "jdbc:postgresql://127.0.0.1:5432/zeroai_ai";

    /**
     * 用户名。建议使用只对知识库表有读写权限的独立账号。
     */
    private String username = "zeroai";

    /**
     * 密码。由环境变量 {@code AI_KNOWLEDGE_PG_PASSWORD} 注入，默认空。
     */
    private String password = "";

    /**
     * 知识块表名。
     * <p>
     * 只允许字母、数字、下划线：表名会拼进 SQL，配置项不能成为注入点，
     * 启动时用白名单校验一次。
     */
    private String table = "ai_knowledge_chunk";

    /**
     * 连接池上限。知识检索是低频读 + 启动时批量写，4 条连接足够。
     */
    private int poolSize = 4;

    /**
     * 获取连接的超时。检索链路整体预算 800ms，这里不能再等更久。
     */
    private long connectionTimeoutMs = 3000L;

    /**
     * 单条 SQL 的查询超时（秒）。慢查询宁可失败也不拖死对话。
     */
    private int queryTimeoutSeconds = 5;

    /**
     * 允许检索的可见范围。默认 {@code public}，与知识文档 frontmatter 一致。
     */
    private String visibility = "public";

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(long connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }
}
