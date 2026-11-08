-- M7 外部动画站内播放：解析任务种类 + 解析结果落外部集（design.md D2）

-- jobs：external.resolve 任务种类（目标为外部集）
ALTER TABLE jobs ADD COLUMN external_episode_id uuid REFERENCES external_episodes(id);

ALTER TABLE jobs DROP CONSTRAINT jobs_kind_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_kind_check
    CHECK (kind IN ('content.verify', 'content.process', 'chapter.parse',
                    'animation.verify', 'index.build', 'external.resolve'));

ALTER TABLE jobs DROP CONSTRAINT jobs_target_by_kind;
ALTER TABLE jobs ADD CONSTRAINT jobs_target_by_kind CHECK (
    (kind = 'content.verify'   AND upload_session_id     IS NOT NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL) OR
    (kind = 'content.process'  AND upload_session_id     IS NOT NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL) OR
    (kind = 'chapter.parse'    AND chapter_revision_id   IS NOT NULL AND upload_session_id   IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL) OR
    (kind = 'animation.verify' AND animation_revision_id IS NOT NULL AND upload_session_id   IS NULL AND chapter_revision_id IS NULL AND index_generation_id IS NULL AND external_episode_id IS NULL) OR
    (kind = 'index.build'      AND index_generation_id   IS NOT NULL AND upload_session_id   IS NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND external_episode_id IS NULL) OR
    (kind = 'external.resolve' AND external_episode_id   IS NOT NULL AND upload_session_id   IS NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL AND index_generation_id IS NULL)
);

-- external_episodes：解析结果（design.md D4：短时效，resolved_at 判新鲜度）
ALTER TABLE external_episodes
    ADD COLUMN resolved_url  text,
    ADD COLUMN resolved_kind text CHECK (resolved_kind IN ('hls', 'mp4')),
    ADD COLUMN resolved_at   timestamptz;

CREATE INDEX idx_jobs_external_episode ON jobs (external_episode_id, created_at);
