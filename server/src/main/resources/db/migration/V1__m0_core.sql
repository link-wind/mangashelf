-- M0 基线迁移：核心扩展 + 上传协议与任务通知所需最小表（DESIGN 8.2 收窄，见 validate-core-stack/design.md D4）
-- users 表在 M1 identity 落地时创建；M0 的 owner_id 使用固定演示 UUID，不做用户外键。

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ── 资产：对象存储中的文件元数据；object_key 由服务端生成（DESIGN 8.2 / 18.1）──
CREATE TABLE assets (
    id               uuid PRIMARY KEY,
    owner_id         uuid NOT NULL,
    object_key       text NOT NULL UNIQUE,
    kind             text NOT NULL CHECK (kind IN ('ORIGINAL', 'READER_IMAGE', 'THUMBNAIL')),
    state            text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'VERIFIED', 'FAILED')),
    size_bytes       bigint,
    declared_sha256  text,
    verified_sha256  text,
    content_type     text,
    metadata         jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

-- ── 上传会话：上传协议状态机（DESIGN 10.2）──
CREATE TABLE upload_sessions (
    id              uuid PRIMARY KEY,
    owner_id        uuid NOT NULL,
    asset_id        uuid REFERENCES assets(id),
    purpose         text NOT NULL CHECK (purpose IN ('MANGA_PAGE')),
    object_key      text NOT NULL,
    expected_size   bigint,
    expected_sha256 text,
    status          text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'VERIFIED', 'FAILED', 'EXPIRED')),
    idempotency_key text NOT NULL,
    request_hash    text NOT NULL,
    expires_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_id, purpose, idempotency_key)
);

-- ── 任务：数据库记录是事实（DESIGN 11）──
-- M0 任务目标只有 upload_session；M1 引入章节／动画版本后按任务种类补充显式外键。
CREATE TABLE jobs (
    id                uuid PRIMARY KEY,
    kind              text NOT NULL CHECK (kind IN ('content.verify', 'content.process')),
    owner_id          uuid NOT NULL,
    upload_session_id uuid NOT NULL REFERENCES upload_sessions(id),
    dedup_key         text NOT NULL,
    status            text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    stage             text,
    progress          int,
    attempt           int NOT NULL DEFAULT 0,
    max_attempt       int NOT NULL DEFAULT 3,
    cancel_requested  boolean NOT NULL DEFAULT false,
    lease_token       uuid,
    lease_until       timestamptz,
    heartbeat_at      timestamptz,
    error_code        text,
    error_message     text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (kind, dedup_key)
);

-- ── 任务事件：任务内序号唯一，支持进度补读（DESIGN 11.4）──
CREATE TABLE job_events (
    job_id     uuid NOT NULL REFERENCES jobs(id),
    sequence   bigint NOT NULL,
    type       text NOT NULL,
    stage      text,
    message    text,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, sequence)
);

-- ── 待派发记录：与任务同事务写入，发布确认后才标记已派发（DESIGN 11.1）──
CREATE TABLE outbox_events (
    id              bigserial PRIMARY KEY,
    job_id          uuid NOT NULL REFERENCES jobs(id),
    dispatch_no     int NOT NULL DEFAULT 1,
    payload         jsonb NOT NULL,
    status          text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DISPATCHED', 'FAILED')),
    attempts        int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (job_id, dispatch_no)
);

-- ── 查询索引（DESIGN 8.4）──
CREATE INDEX idx_jobs_kind_status ON jobs (kind, status, created_at);
CREATE INDEX idx_jobs_session ON jobs (upload_session_id);
CREATE INDEX idx_outbox_pending ON outbox_events (status, next_attempt_at);
CREATE INDEX idx_assets_owner ON assets (owner_id, created_at);
CREATE INDEX idx_upload_sessions_owner ON upload_sessions (owner_id, created_at);
