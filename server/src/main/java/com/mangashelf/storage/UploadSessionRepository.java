package com.mangashelf.storage;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UploadSessionRepository {

    /** M0 会话有效期：未完成上传按期限清理（DESIGN 18.2），额度与期限可配置化放到 M1。 */
    public static final Duration SESSION_TTL = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final RowMapper<UploadSession> mapper = new DataClassRowMapper<>(UploadSession.class);

    public UploadSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UploadSession session) {
        jdbc.update("""
                INSERT INTO upload_sessions (id, owner_id, asset_id, purpose, object_key, expected_size,
                                            expected_sha256, status, idempotency_key, request_hash, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, session.id(), session.ownerId(), session.assetId(), session.purpose(),
                session.objectKey(), session.expectedSize(), session.expectedSha256(),
                session.status(), session.idempotencyKey(), session.requestHash(), session.expiresAt());
    }

    public Optional<UploadSession> findById(UUID id) {
        return jdbc.query("SELECT * FROM upload_sessions WHERE id = ?", mapper, id).stream().findFirst();
    }

    public Optional<UploadSession> findByIdempotencyKey(UUID ownerId, String purpose, String idempotencyKey) {
        return jdbc.query(
                "SELECT * FROM upload_sessions WHERE owner_id = ? AND purpose = ? AND idempotency_key = ?",
                mapper, ownerId, purpose, idempotencyKey).stream().findFirst();
    }

    public void markStatus(UUID id, String status) {
        jdbc.update("UPDATE upload_sessions SET status = ?, updated_at = now() WHERE id = ?", status, id);
    }

    public int countExpiredPending(Instant now) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM upload_sessions WHERE status = 'PENDING' AND expires_at < ?",
                Integer.class, now);
        return n == null ? 0 : n;
    }
}
