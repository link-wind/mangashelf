-- M4 迁移（M3 功能）：接入 Token、作品阅读方向、动画版本的协议来源引用
-- （DESIGN 8.2 / 16.1 / 17）。

-- ── 接入 Token（DESIGN 8.2 api_tokens：原始值仅创建时展示，库存安全哈希）──
CREATE TABLE api_tokens (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users(id),
    token_hash text NOT NULL UNIQUE,
    prefix     text NOT NULL,
    scopes     text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    revoked_at timestamptz
);

CREATE INDEX idx_api_tokens_user ON api_tokens (user_id, created_at);

-- ── 作品阅读方向（DESIGN 17.2 导出清单字段；日漫默认右开）──
ALTER TABLE works ADD COLUMN reading_direction text NOT NULL DEFAULT 'rtl'
    CHECK (reading_direction IN ('rtl', 'ltr'));

-- ── 动画版本的协议来源引用（DESIGN 8.2 origin_client / origin_run_ref）──
ALTER TABLE animation_revisions ADD COLUMN origin_client text;
ALTER TABLE animation_revisions ADD COLUMN origin_run_ref text;

CREATE INDEX idx_arevisions_origin ON animation_revisions (origin_client, origin_run_ref)
    WHERE origin_client IS NOT NULL AND origin_run_ref IS NOT NULL;
