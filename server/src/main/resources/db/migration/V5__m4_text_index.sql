-- M5 迁移（M4 功能第一阶段）：页级文本索引结构与批次（DESIGN 8.2 / 15）。
-- 向量列不在本迁移：维度必须由首个实测验证的 Embedding 模型决定后
-- 经 V6 迁移补充（implement-intelligent-retrieval design.md D1）。

-- ── 索引批次（DESIGN 8.2 index_generations / 9：BUILDING→READY→ACTIVE）──
CREATE TABLE index_generations (
    id                    uuid PRIMARY KEY,
    chapter_revision_id   uuid NOT NULL REFERENCES chapter_revisions(id),
    status                text NOT NULL DEFAULT 'BUILDING'
                          CHECK (status IN ('BUILDING', 'READY', 'ACTIVE', 'FAILED')),
    embedding_model       text,
    dimensions            int,
    pipeline_version      text NOT NULL,
    source_manifest_sha256 text NOT NULL,
    error_code            text,
    error_message         text,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now()
);

-- 同版本最多一个 ACTIVE 批次（部分唯一索引，DESIGN 8.3）
CREATE UNIQUE INDEX uq_index_active ON index_generations (chapter_revision_id)
    WHERE status = 'ACTIVE';

-- ── 索引块（页级文本；来源 ID 可核验，DESIGN 8.2 search_chunks）──
CREATE TABLE search_chunks (
    id                  bigserial PRIMARY KEY,
    index_generation_id uuid NOT NULL REFERENCES index_generations(id),
    chapter_revision_id uuid NOT NULL REFERENCES chapter_revisions(id),
    page_id             uuid REFERENCES pages(id),
    chunk_kind          text NOT NULL DEFAULT 'page' CHECK (chunk_kind IN ('page')),
    text                text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now()
);

-- 中文按字 trigram：短词命中可用（语义由 V6 向量分支承担，DESIGN 15.3）
CREATE INDEX idx_chunks_text_trgm ON search_chunks USING gin (text gin_trgm_ops);
CREATE INDEX idx_chunks_generation ON search_chunks (index_generation_id);
CREATE INDEX idx_chunks_page ON search_chunks (page_id);

-- ── jobs：index.build 任务种类（目标索引批次，DESIGN 11.1 content.index 队列）──
ALTER TABLE jobs ADD COLUMN index_generation_id uuid REFERENCES index_generations(id);

ALTER TABLE jobs DROP CONSTRAINT jobs_kind_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_kind_check
    CHECK (kind IN ('content.verify', 'content.process', 'chapter.parse',
                    'animation.verify', 'index.build'));

ALTER TABLE jobs DROP CONSTRAINT jobs_target_by_kind;
ALTER TABLE jobs ADD CONSTRAINT jobs_target_by_kind CHECK (
    (kind = 'content.verify'   AND upload_session_id     IS NOT NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL) OR
    (kind = 'content.process'  AND upload_session_id     IS NOT NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL) OR
    (kind = 'chapter.parse'    AND chapter_revision_id   IS NOT NULL AND upload_session_id   IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL) OR
    (kind = 'animation.verify' AND animation_revision_id IS NOT NULL AND upload_session_id   IS NULL AND chapter_revision_id IS NULL AND index_generation_id IS NULL) OR
    (kind = 'index.build'      AND index_generation_id   IS NOT NULL AND upload_session_id   IS NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL)
);

-- ── 查询索引 ──
CREATE INDEX idx_generations_revision ON index_generations (chapter_revision_id, created_at);
