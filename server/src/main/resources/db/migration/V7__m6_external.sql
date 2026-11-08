-- M7 迁移（M6 功能）：外部动画源与外部集绑定（DESIGN 17 后续 / 调研方案 A）。

-- ── 规则源（管理员导入，Kazumi 兼容 JSON）──
CREATE TABLE external_sources (
    id          uuid PRIMARY KEY,
    name        text NOT NULL UNIQUE,
    base_url    text NOT NULL,
    rules       jsonb NOT NULL,
    enabled     boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- ── 外部集绑定（作品所有者操作；来源 ID 回链，DESIGN 17.2 模式）──
CREATE TABLE external_episodes (
    id           uuid PRIMARY KEY,
    work_id      uuid NOT NULL REFERENCES works(id),
    source_id    uuid NOT NULL REFERENCES external_sources(id),
    title        text NOT NULL,
    episode_url  text NOT NULL,
    ordinal      int NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (work_id, source_id, ordinal)
);

CREATE INDEX idx_external_episodes_work ON external_episodes (work_id, ordinal);
CREATE INDEX idx_external_episodes_source ON external_episodes (source_id);
