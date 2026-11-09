-- M10/M11：多线路剧集、外部集服务端进度与观看历史、规则源 Cookie

-- ── 多线路：episodes 增加线路维度（资源站一个条目常有多条线路）──
ALTER TABLE external_episodes ADD COLUMN line_name text NOT NULL DEFAULT '';
ALTER TABLE external_episodes ADD COLUMN line_rank int NOT NULL DEFAULT 0;
ALTER TABLE external_episodes ADD COLUMN episode_type text NOT NULL DEFAULT 'MAIN'
    CHECK (episode_type IN ('MAIN', 'EXTRA'));

ALTER TABLE external_episodes DROP CONSTRAINT external_episodes_work_id_source_id_ordinal_key;
ALTER TABLE external_episodes
    ADD CONSTRAINT external_episodes_work_line_ordinal_key
    UNIQUE (work_id, source_id, line_name, ordinal);

CREATE INDEX idx_external_episodes_work_line ON external_episodes (work_id, line_rank, ordinal);

-- ── 外部集观看进度（服务端）：按 (user, work, ordinal) 记录，切线路不丢进度 ──
CREATE TABLE external_watch_progress (
    user_id       uuid NOT NULL REFERENCES users(id),
    work_id       uuid NOT NULL REFERENCES works(id),
    ordinal       int  NOT NULL,
    episode_id    uuid REFERENCES external_episodes(id) ON DELETE SET NULL,
    position_ms   bigint NOT NULL CHECK (position_ms >= 0),
    duration_ms   bigint,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, work_id, ordinal)
);

CREATE INDEX idx_ext_progress_recent ON external_watch_progress (user_id, updated_at DESC);

-- ── 规则源 Cookie（登录墙源；resolve 时随请求下发，仅服务端持有）──
ALTER TABLE external_sources ADD COLUMN cookie text NOT NULL DEFAULT '';
