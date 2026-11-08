-- M1 迁移：身份、目录（作品/章节/版本/页面）、书架与阅读进度；
-- 并完成 M0 收窄项回填（owner 外键、jobs 按任务种类约束，DESIGN 8.2/8.3）。

-- ── 用户（DESIGN 8.2 users；登录标识唯一，密码只存安全哈希）──
CREATE TABLE users (
    id            uuid PRIMARY KEY,
    login         text NOT NULL UNIQUE,
    password_hash text NOT NULL,
    role          text NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    status        text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- ── 作品（可见性与对外状态分离，DESIGN 3.2 / 9）──
CREATE TABLE works (
    id          uuid PRIMARY KEY,
    owner_id    uuid NOT NULL REFERENCES users(id),
    title       text NOT NULL,
    description text NOT NULL DEFAULT '',
    visibility  text NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
    status      text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- ── 章节（同作品顺序唯一；current_revision 归属用组合外键保证，DESIGN 8.3）──
CREATE TABLE chapters (
    id                  uuid PRIMARY KEY,
    work_id             uuid NOT NULL REFERENCES works(id),
    ordinal             int NOT NULL,
    title               text NOT NULL DEFAULT '',
    current_revision_id uuid,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (work_id, ordinal)
);

-- ── 章节版本（发布后清单不可原地改写，DESIGN 8.2 / 9）──
CREATE TABLE chapter_revisions (
    id               uuid PRIMARY KEY,
    chapter_id       uuid NOT NULL REFERENCES chapters(id),
    revision_no      int NOT NULL,
    edit_version     int NOT NULL DEFAULT 0,
    source_asset_id  uuid NOT NULL REFERENCES assets(id),
    status           text NOT NULL DEFAULT 'DRAFT'
                     CHECK (status IN ('DRAFT', 'PROCESSING', 'READY', 'PUBLISHED', 'FAILED')),
    page_count       int NOT NULL DEFAULT 0,
    manifest_sha256  text,
    error_code       text,
    error_message    text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (chapter_id, revision_no),
    UNIQUE (id, chapter_id),
    UNIQUE (chapter_id, id)
);

-- 组合外键（DESIGN 8.3）：引用列为 chapters 自己的 (id, current_revision_id)，
-- 保证当前版本指针必然指向属于本章节的版本；NULL 时按 MATCH SIMPLE 不校验
ALTER TABLE chapters
    ADD CONSTRAINT fk_chapters_current_revision
    FOREIGN KEY (id, current_revision_id)
    REFERENCES chapter_revisions (chapter_id, id);

-- ── 页面（同版本页序唯一；图片必需，缩略图可待补，DESIGN 8.2）──
CREATE TABLE pages (
    id                  uuid PRIMARY KEY,
    chapter_revision_id uuid NOT NULL REFERENCES chapter_revisions(id),
    ordinal             int NOT NULL,
    image_asset_id      uuid NOT NULL REFERENCES assets(id),
    thumbnail_asset_id  uuid REFERENCES assets(id),
    width               int NOT NULL,
    height              int NOT NULL,
    UNIQUE (chapter_revision_id, ordinal)
);

-- ── 书架与阅读进度（DESIGN 8.2；进度版本号防覆盖）──
CREATE TABLE library_entries (
    user_id    uuid NOT NULL REFERENCES users(id),
    work_id    uuid NOT NULL REFERENCES works(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, work_id)
);

CREATE TABLE reading_progress (
    user_id             uuid NOT NULL REFERENCES users(id),
    chapter_id          uuid NOT NULL REFERENCES chapters(id),
    chapter_revision_id uuid NOT NULL REFERENCES chapter_revisions(id),
    page_ordinal        int NOT NULL,
    version             int NOT NULL DEFAULT 1,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, chapter_id)
);

-- ══ M0 收窄回填 ══
-- 演示 owner 落为真实用户行（引导管理员；密码哈希由应用启动逻辑补齐前该行仅用于既有数据归属）
INSERT INTO users (id, login, password_hash, role)
VALUES ('00000000-0000-0000-0000-000000000001', 'admin', 'PENDING_BOOTSTRAP', 'ADMIN');

ALTER TABLE assets           ADD CONSTRAINT fk_assets_owner  FOREIGN KEY (owner_id) REFERENCES users(id);
ALTER TABLE upload_sessions  ADD CONSTRAINT fk_sessions_owner FOREIGN KEY (owner_id) REFERENCES users(id);
ALTER TABLE jobs             ADD CONSTRAINT fk_jobs_owner     FOREIGN KEY (owner_id) REFERENCES users(id);

-- jobs：新增章节版本目标，种类扩展，并按任务种类约束目标唯一性（DESIGN 8.2）
-- upload_session_id 放宽为可空：chapter.parse 的目标是章节版本而非上传
ALTER TABLE jobs ALTER COLUMN upload_session_id DROP NOT NULL;
ALTER TABLE jobs ADD COLUMN chapter_revision_id uuid;
ALTER TABLE jobs ADD CONSTRAINT fk_jobs_revision FOREIGN KEY (chapter_revision_id) REFERENCES chapter_revisions(id);

ALTER TABLE jobs DROP CONSTRAINT jobs_kind_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_kind_check
    CHECK (kind IN ('content.verify', 'content.process', 'chapter.parse'));

ALTER TABLE jobs ADD CONSTRAINT jobs_target_by_kind CHECK (
    (kind = 'content.verify'  AND upload_session_id IS NOT NULL AND chapter_revision_id IS NULL) OR
    (kind = 'content.process' AND upload_session_id IS NOT NULL AND chapter_revision_id IS NULL) OR
    (kind = 'chapter.parse'   AND upload_session_id IS NULL     AND chapter_revision_id IS NOT NULL)
);

-- ── 查询索引（DESIGN 8.4：按实际查询建）──
CREATE INDEX idx_works_owner        ON works (owner_id, created_at);
CREATE INDEX idx_works_public       ON works (status, visibility, created_at) WHERE visibility = 'PUBLIC';
CREATE INDEX idx_chapters_work      ON chapters (work_id, ordinal);
CREATE INDEX idx_revisions_chapter  ON chapter_revisions (chapter_id, revision_no);
CREATE INDEX idx_revisions_status   ON chapter_revisions (status) WHERE status IN ('DRAFT', 'PROCESSING', 'READY');
CREATE INDEX idx_pages_revision     ON pages (chapter_revision_id, ordinal);
CREATE INDEX idx_jobs_revision      ON jobs (chapter_revision_id);
CREATE INDEX idx_library_work       ON library_entries (work_id);
