-- 收藏增加「在追 / 已看完（已读完）」状态，约束下沉数据库。
ALTER TABLE favorites
    ADD COLUMN IF NOT EXISTS status varchar(16) NOT NULL DEFAULT 'WATCHING'
        CHECK (status IN ('WATCHING', 'COMPLETED')),
    ADD COLUMN IF NOT EXISTS completed_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_favorites_user_status ON favorites (user_id, status);
