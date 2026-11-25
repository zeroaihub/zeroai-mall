-- 知识库向量表（PGVector）。
--
-- 只由 zeroai.ai.knowledge.vector.store=pgvector 时使用；
-- 商品实时数据仍在 Elasticsearch / MySQL，本表只存规则类知识块。
--
-- 维度说明：vector(1024) 必须与 zeroai.ai.knowledge.embedding.dimension 一致。
-- 应用启动时会用 format_type() 校验，不一致直接拒绝检索（而不是返回错空间的结果）。
-- 换 embedding 模型（维度变化）时必须重建本表，并清空旧向量。

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS ai_knowledge_chunk (
    id              bigserial    PRIMARY KEY,
    -- 文档稳定标识（frontmatter 的 sourceId），同一文档内容更新按 source_id 覆盖
    source_id       varchar(128) NOT NULL,
    -- 文档内块序号，从 0 开始；文档变短时按序号删除尾部旧块
    chunk_index     integer      NOT NULL,
    title           varchar(256) NOT NULL,
    section         varchar(256),
    category        varchar(64),
    tags            text[]       NOT NULL DEFAULT '{}',
    -- public 进公共检索；internal 只允许内部链路，由 pg.visibility 过滤
    visibility      varchar(16)  NOT NULL DEFAULT 'public',
    version         integer      NOT NULL DEFAULT 1,
    updated_at      date         NOT NULL,
    -- 带「【标题 · 小节】」前缀的块文本，直接给模型看
    content         text         NOT NULL,
    -- sha256(content)，增量同步只重算变化的块
    content_hash    char(64)     NOT NULL,
    embedding       vector(1024) NOT NULL,
    embedding_model varchar(64)  NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_ts      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_kb_chunk UNIQUE (source_id, chunk_index)
);

-- 检索固定带 visibility 过滤；语料上千条后这个索引才开始有意义
CREATE INDEX IF NOT EXISTS idx_kb_chunk_visibility
    ON ai_knowledge_chunk (visibility);

-- 百级数据量下 planner 会走顺序扫描，HNSW 是为后续增长预留的；可安全删除
CREATE INDEX IF NOT EXISTS idx_kb_chunk_embedding
    ON ai_knowledge_chunk USING hnsw (embedding vector_cosine_ops);
