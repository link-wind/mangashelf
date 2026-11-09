-- M9 Bangumi 元数据层：本季新番日历缓存 + 目录条目桥接

CREATE TABLE bangumi_subjects (
    subject_id   bigint PRIMARY KEY,          -- bgm.tv subject id
    name         text NOT NULL,
    name_cn      text NOT NULL DEFAULT '',
    cover_url    text NOT NULL DEFAULT '',
    rating       numeric(4,1),
    air_date     date,
    air_weekday  int CHECK (air_weekday BETWEEN 1 AND 7),
    summary      text NOT NULL DEFAULT '',
    in_calendar  boolean NOT NULL DEFAULT false,  -- 是否本季放送（calendar 同步标记）
    synced_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_bangumi_weekday ON bangumi_subjects (air_weekday) WHERE in_calendar;

ALTER TABLE external_catalog_items
    ADD COLUMN bangumi_subject_id bigint REFERENCES bangumi_subjects(subject_id);
CREATE INDEX idx_catalog_items_subject ON external_catalog_items (bangumi_subject_id);

-- jobs：bangumi.sync 任务种类（无目标列，参数全走 params）
ALTER TABLE jobs DROP CONSTRAINT jobs_kind_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_kind_check
    CHECK (kind IN ('content.verify', 'content.process', 'chapter.parse',
                    'animation.verify', 'index.build', 'external.resolve', 'catalog.sync',
                    'bangumi.sync'));

ALTER TABLE jobs DROP CONSTRAINT jobs_target_by_kind;
ALTER TABLE jobs ADD CONSTRAINT jobs_target_by_kind CHECK (
    (kind = 'content.verify'   AND upload_session_id     IS NOT NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL) OR
    (kind = 'content.process'  AND upload_session_id     IS NOT NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL) OR
    (kind = 'chapter.parse'    AND chapter_revision_id   IS NOT NULL AND upload_session_id   IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL) OR
    (kind = 'animation.verify' AND animation_revision_id IS NOT NULL AND upload_session_id   IS NULL AND chapter_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL) OR
    (kind = 'index.build'      AND index_generation_id   IS NOT NULL AND upload_session_id   IS NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL) OR
    (kind = 'external.resolve' AND external_episode_id   IS NOT NULL AND upload_session_id   IS NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND sync_source_id IS NULL) OR
    (kind = 'catalog.sync'     AND sync_source_id        IS NOT NULL AND upload_session_id   IS NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL) OR
    (kind = 'bangumi.sync'     AND upload_session_id     IS NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL AND sync_source_id IS NULL)
);
