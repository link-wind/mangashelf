-- 漫画目录落库（manga-catalog-sync）：全量/渐进式同步的目录条目。
CREATE TABLE manga_catalog_items (
    id             uuid PRIMARY KEY,
    source         text NOT NULL,
    source_item_id text NOT NULL,
    name           text NOT NULL,
    alt_title      text NOT NULL DEFAULT '',
    cover_url      text NOT NULL DEFAULT '',
    remarks        text NOT NULL DEFAULT '',
    tags           text[] NOT NULL DEFAULT '{}',
    episode_count  int,
    synced_at      timestamptz NOT NULL DEFAULT now(),
    first_seen_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source, source_item_id)
);

-- 题材数组查询（漫画索引题材 tab）
CREATE INDEX idx_manga_catalog_tags ON manga_catalog_items USING gin (tags);
-- 标题模糊搜索（pg_trgm，扩展随 V6 启用）
CREATE INDEX idx_manga_catalog_name_trgm ON manga_catalog_items USING gin (name gin_trgm_ops);
-- 索引页分页排序
CREATE INDEX idx_manga_catalog_source_recent ON manga_catalog_items (source, synced_at DESC);

-- 同步任务种类扩展：manga.catalog.sync 无业务目标行（params.sourceId 指定源）
ALTER TABLE jobs DROP CONSTRAINT jobs_target_by_kind;
ALTER TABLE jobs ADD CONSTRAINT jobs_target_by_kind CHECK (
    kind = 'content.verify' AND upload_session_id IS NOT NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL
 OR kind = 'content.process' AND upload_session_id IS NOT NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL
 OR kind = 'chapter.parse' AND chapter_revision_id IS NOT NULL AND upload_session_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL
 OR kind = 'animation.verify' AND animation_revision_id IS NOT NULL AND upload_session_id IS NULL AND chapter_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL
 OR kind = 'index.build' AND index_generation_id IS NOT NULL AND upload_session_id IS NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL
 OR kind = 'external.resolve' AND external_episode_id IS NOT NULL AND upload_session_id IS NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND sync_source_id IS NULL
 OR kind = 'catalog.sync' AND sync_source_id IS NOT NULL AND upload_session_id IS NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL
 OR kind = 'bangumi.sync' AND upload_session_id IS NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL
 OR kind = 'manga.catalog.sync' AND upload_session_id IS NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL
);
