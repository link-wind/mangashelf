-- 统一收藏（本地作品 / 外部漫画 / 外部番剧目录条目）与浏览态播放进度。
CREATE TABLE favorites (
    id              uuid PRIMARY KEY,
    user_id         uuid NOT NULL REFERENCES users(id),
    work_id         uuid REFERENCES works(id),
    manga_source    text,
    manga_id        text,
    manga_title     text,
    catalog_item_id uuid REFERENCES external_catalog_items(id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    -- 三类目标必须恰好一个；外部漫画以 (source, manga_id) 成对出现。
    CHECK (
        (work_id IS NOT NULL)::int
        + ((manga_source IS NOT NULL AND manga_source <> ''
            AND manga_id IS NOT NULL AND manga_id <> ''))::int
        + (catalog_item_id IS NOT NULL)::int = 1
    ),
    CHECK (manga_title IS NULL OR manga_id IS NOT NULL)
);

CREATE UNIQUE INDEX favorites_work_key ON favorites (user_id, work_id) WHERE work_id IS NOT NULL;
CREATE UNIQUE INDEX favorites_manga_key ON favorites (user_id, manga_source, manga_id) WHERE manga_id IS NOT NULL;
CREATE UNIQUE INDEX favorites_catalog_key ON favorites (user_id, catalog_item_id) WHERE catalog_item_id IS NOT NULL;
CREATE INDEX idx_favorites_recent ON favorites (user_id, created_at DESC);

-- 既有书架收藏迁移为作品收藏，随后退役旧表。
INSERT INTO favorites (id, user_id, work_id, created_at)
SELECT gen_random_uuid(), user_id, work_id, created_at FROM library_entries;
DROP TABLE library_entries;

-- 浏览态播放进度：键为（目录条目, 集号），与外部集进度同版本语义。
-- 剧集物化后仍保留 catalog_item_id，因此导入前后进度键稳定。
CREATE TABLE catalog_watch_progress (
    user_id         uuid NOT NULL REFERENCES users(id),
    catalog_item_id uuid NOT NULL REFERENCES external_catalog_items(id),
    ordinal         int  NOT NULL,
    episode_id      uuid REFERENCES external_episodes(id) ON DELETE SET NULL,
    position_ms     bigint NOT NULL CHECK (position_ms >= 0),
    duration_ms     bigint,
    version         int  NOT NULL DEFAULT 1,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, catalog_item_id, ordinal)
);

CREATE INDEX idx_catalog_watch_recent ON catalog_watch_progress (user_id, updated_at DESC);
