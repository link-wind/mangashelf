-- 统一站内结构化检索文档表（benchmark-and-optimize-hybrid-search）：
-- 不依赖图片 OCR，直接聚合站内作品、动画、番剧目录（含 Bangumi 元数据）与漫画目录文本，
-- 支持前置权限过滤下的 pg_trgm 字面召回 + pgvector 384 维余弦语义召回 + RRF / Rerank。

CREATE TABLE search_documents (
    id              bigserial PRIMARY KEY,
    doc_type        text NOT NULL CHECK (doc_type IN ('WORK', 'ANIMATION', 'ANIME_CATALOG', 'MANGA_CATALOG')),
    target_id       uuid NOT NULL,
    owner_id        uuid,
    is_public       boolean NOT NULL DEFAULT false,
    title           text NOT NULL,
    alt_titles      text NOT NULL DEFAULT '',
    body            text NOT NULL DEFAULT '',
    search_text     text NOT NULL,
    embedding       vector(384),
    embedding_model text,
    source_label    text NOT NULL DEFAULT '',
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (doc_type, target_id)
);

CREATE INDEX idx_search_docs_text_trgm ON search_documents USING gin (search_text gin_trgm_ops);
CREATE INDEX idx_search_docs_title_trgm ON search_documents USING gin (title gin_trgm_ops);
CREATE INDEX idx_search_docs_embedding_hnsw ON search_documents USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_search_docs_visibility ON search_documents (is_public, owner_id, doc_type);
