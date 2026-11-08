-- M8 资源站目录同步：目录层与书库分层（design.md D1）

CREATE TABLE external_catalog_items (
    id            uuid PRIMARY KEY,
    source_id     uuid NOT NULL REFERENCES external_sources(id),
    vod_id        bigint NOT NULL,
    name          text NOT NULL,
    cover_url     text NOT NULL DEFAULT '',
    remarks       text NOT NULL DEFAULT '',
    category_id   int NOT NULL,
    category_name text NOT NULL DEFAULT '',
    play_from     text NOT NULL DEFAULT '',
    play_groups   jsonb NOT NULL,
    episode_count int NOT NULL DEFAULT 0,
    work_id       uuid REFERENCES works(id),
    resource_updated_at timestamptz,
    synced_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_id, vod_id)
);

CREATE INDEX idx_catalog_items_name_trgm ON external_catalog_items USING gin (name gin_trgm_ops);
CREATE INDEX idx_catalog_items_category ON external_catalog_items (category_id, name);
CREATE INDEX idx_catalog_items_source ON external_catalog_items (source_id);

-- jobs：catalog.sync 任务种类（目标为资源源）
ALTER TABLE jobs ADD COLUMN sync_source_id uuid REFERENCES external_sources(id);
ALTER TABLE jobs ADD COLUMN params jsonb;

ALTER TABLE jobs DROP CONSTRAINT jobs_kind_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_kind_check
    CHECK (kind IN ('content.verify', 'content.process', 'chapter.parse',
                    'animation.verify', 'index.build', 'external.resolve', 'catalog.sync'));

ALTER TABLE jobs DROP CONSTRAINT jobs_target_by_kind;
ALTER TABLE jobs ADD CONSTRAINT jobs_target_by_kind CHECK (
    (kind = 'content.verify'   AND upload_session_id     IS NOT NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL) OR
    (kind = 'content.process'  AND upload_session_id     IS NOT NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL) OR
    (kind = 'chapter.parse'    AND chapter_revision_id   IS NOT NULL AND upload_session_id   IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL) OR
    (kind = 'animation.verify' AND animation_revision_id IS NOT NULL AND upload_session_id   IS NULL AND chapter_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL) OR
    (kind = 'index.build'      AND index_generation_id   IS NOT NULL AND upload_session_id   IS NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL) OR
    (kind = 'external.resolve' AND external_episode_id   IS NOT NULL AND upload_session_id   IS NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND sync_source_id IS NULL) OR
    (kind = 'catalog.sync'     AND sync_source_id        IS NOT NULL AND upload_session_id   IS NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL)
);

CREATE INDEX idx_jobs_sync_source ON jobs (sync_source_id, created_at);
