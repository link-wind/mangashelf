-- V37: 用户邮箱字段与注册邮箱验证码表（验证码仅存 SHA-256 哈希，不落明文）

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email text,
    ADD COLUMN IF NOT EXISTS email_verified_at timestamptz;

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower
    ON users (lower(email))
    WHERE email IS NOT NULL;

CREATE TABLE IF NOT EXISTS email_verification_codes (
    id           uuid PRIMARY KEY,
    email        text NOT NULL,
    purpose      text NOT NULL CHECK (purpose IN ('REGISTER')),
    code_hash    text NOT NULL,
    request_ip   text NOT NULL DEFAULT '',
    attempts     int NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts int NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    expires_at   timestamptz NOT NULL,
    consumed_at  timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_evc_email_purpose_created
    ON email_verification_codes (email, purpose, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_evc_ip_created
    ON email_verification_codes (request_ip, created_at DESC);
