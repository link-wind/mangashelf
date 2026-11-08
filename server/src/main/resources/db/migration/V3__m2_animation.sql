-- M3 迁移（M2 功能）：动画容器、版本、来源映射、观看进度；
-- 上传用途与资产种类扩展；jobs 新增 animation.verify 任务种类（DESIGN 8.2 / 13）。

-- ── 动画容器（DESIGN 8.2 animations；可见性与对外状态分离）──
CREATE TABLE animations (
    id                  uuid PRIMARY KEY,
    owner_id            uuid NOT NULL REFERENCES users(id),
    title               text NOT NULL,
    description         text NOT NULL DEFAULT '',
    visibility          text NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
    status              text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN')),
    current_revision_id uuid,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- ── 动画版本（DESIGN 8.2 / 9：媒体校验通过后才能 READY；发布后切当前版本）──
CREATE TABLE animation_revisions (
    id              uuid PRIMARY KEY,
    animation_id    uuid NOT NULL REFERENCES animations(id),
    revision_no     int NOT NULL,
    edit_version    int NOT NULL DEFAULT 0,
    status          text NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT', 'VERIFYING', 'READY', 'PUBLISHED', 'FAILED')),
    video_asset_id  uuid REFERENCES assets(id),
    poster_asset_id uuid REFERENCES assets(id),
    subtitle_asset_id uuid REFERENCES assets(id),
    duration_ms     bigint,
    video_width     int,
    video_height    int,
    video_codec     text,
    audio_codec     text,
    has_audio       boolean,
    source_manifest_sha256 text,
    error_code      text,
    error_message   text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (animation_id, revision_no),
    UNIQUE (id, animation_id),
    UNIQUE (animation_id, id)
);

-- 组合外键（DESIGN 8.3）：当前版本指针必然属于本动画
ALTER TABLE animations
    ADD CONSTRAINT fk_animations_current_revision
    FOREIGN KEY (id, current_revision_id)
    REFERENCES animation_revisions (animation_id, id);

-- ── 来源映射（DESIGN 13.2：固定章节版本 + 自然页码范围 + 顺序；跨表范围在事务校验）──
CREATE TABLE animation_sources (
    id                    uuid PRIMARY KEY,
    animation_revision_id uuid NOT NULL REFERENCES animation_revisions(id),
    chapter_revision_id   uuid NOT NULL REFERENCES chapter_revisions(id),
    page_start            int NOT NULL,
    page_end              int NOT NULL,
    ordinal               int NOT NULL,
    UNIQUE (animation_revision_id, ordinal),
    CHECK (page_start >= 1 AND page_start <= page_end)
);

-- ── 观看进度（DESIGN 8.2 watch_progress：与阅读进度分表；旧进度绑定旧版本）──
CREATE TABLE watch_progress (
    user_id             uuid NOT NULL REFERENCES users(id),
    animation_id        uuid NOT NULL REFERENCES animations(id),
    animation_revision_id uuid NOT NULL REFERENCES animation_revisions(id),
    position_ms         bigint NOT NULL DEFAULT 0,
    version             int NOT NULL DEFAULT 1,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, animation_id)
);

-- ── 上传用途与资产种类扩展 ──
ALTER TABLE upload_sessions DROP CONSTRAINT upload_sessions_purpose_check;
ALTER TABLE upload_sessions ADD CONSTRAINT upload_sessions_purpose_check
    CHECK (purpose IN ('MANGA_PAGE', 'ANIMATION_VIDEO', 'ANIMATION_POSTER', 'ANIMATION_SUBTITLE'));

ALTER TABLE assets DROP CONSTRAINT assets_kind_check;
ALTER TABLE assets ADD CONSTRAINT assets_kind_check
    CHECK (kind IN ('ORIGINAL', 'READER_IMAGE', 'THUMBNAIL', 'VIDEO', 'POSTER', 'SUBTITLE'));

-- ── jobs：animation.verify 任务种类（目标为动画版本，DESIGN 8.2 按任务种类约束）──
ALTER TABLE jobs ADD COLUMN animation_revision_id uuid REFERENCES animation_revisions(id);

ALTER TABLE jobs DROP CONSTRAINT jobs_kind_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_kind_check
    CHECK (kind IN ('content.verify', 'content.process', 'chapter.parse', 'animation.verify'));

ALTER TABLE jobs DROP CONSTRAINT jobs_target_by_kind;
ALTER TABLE jobs ADD CONSTRAINT jobs_target_by_kind CHECK (
    (kind = 'content.verify'   AND upload_session_id   IS NOT NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL) OR
    (kind = 'content.process'  AND upload_session_id   IS NOT NULL AND chapter_revision_id IS NULL AND animation_revision_id IS NULL) OR
    (kind = 'chapter.parse'    AND chapter_revision_id IS NOT NULL AND upload_session_id   IS NULL AND animation_revision_id IS NULL) OR
    (kind = 'animation.verify' AND animation_revision_id IS NOT NULL AND upload_session_id  IS NULL AND chapter_revision_id IS NULL)
);

-- ── 查询索引（DESIGN 8.4）──
CREATE INDEX idx_animations_owner  ON animations (owner_id, created_at);
CREATE INDEX idx_animations_public ON animations (status, visibility, created_at) WHERE visibility = 'PUBLIC';
CREATE INDEX idx_arevisions_animation ON animation_revisions (animation_id, revision_no);
CREATE INDEX idx_asources_revision ON animation_sources (animation_revision_id, ordinal);
CREATE INDEX idx_asources_chapter  ON animation_sources (chapter_revision_id);
