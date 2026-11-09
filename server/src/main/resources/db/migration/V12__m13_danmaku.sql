-- M13：站内弹幕（按作品+集号存储，时间轴毫秒）
CREATE TABLE danmaku (
    id         uuid PRIMARY KEY,
    work_id    uuid NOT NULL REFERENCES works(id),
    ordinal    int  NOT NULL CHECK (ordinal >= 0),
    episode_id uuid REFERENCES external_episodes(id) ON DELETE SET NULL,
    user_id    uuid NOT NULL REFERENCES users(id),
    time_ms    bigint NOT NULL CHECK (time_ms >= 0),
    content    text NOT NULL CHECK (char_length(content) BETWEEN 1 AND 200),
    color      text NOT NULL DEFAULT '#ffffff' CHECK (color ~ '^#[0-9a-fA-F]{6}$'),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_danmaku_timeline ON danmaku (work_id, ordinal, time_ms);
CREATE INDEX idx_danmaku_user ON danmaku (user_id, created_at DESC);
