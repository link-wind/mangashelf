-- M18：外部漫画阅读进度（服务端，跨设备续读）
CREATE TABLE external_manga_progress (
    user_id       uuid NOT NULL REFERENCES users(id),
    source        text NOT NULL,
    manga_id      text NOT NULL,
    chapter_id    text,
    chapter_label text,
    page          int  NOT NULL DEFAULT 1 CHECK (page >= 0),
    total_pages   int,
    title         text NOT NULL,
    cover_url     text,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, source, manga_id)
);

CREATE INDEX idx_ext_manga_progress_recent ON external_manga_progress (user_id, updated_at DESC);
