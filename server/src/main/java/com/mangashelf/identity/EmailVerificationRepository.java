package com.mangashelf.identity;

import com.mangashelf.config.JdbcTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** 注册邮箱验证码仓储（仅存 SHA-256 哈希，不落明文）。 */
@Repository
public class EmailVerificationRepository {

    public static final String PURPOSE_REGISTER = "REGISTER";

    public record VerificationCode(
            UUID id,
            String email,
            String purpose,
            String codeHash,
            String requestIp,
            int attempts,
            int maxAttempts,
            Instant expiresAt,
            Instant consumedAt,
            Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    private final RowMapper<VerificationCode> mapper = (rs, i) -> {
        OffsetDateTime consumed = rs.getObject("consumed_at", OffsetDateTime.class);
        return new VerificationCode(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("purpose"),
                rs.getString("code_hash"),
                rs.getString("request_ip"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                consumed == null ? null : consumed.toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    };

    public EmailVerificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String email, String purpose, String codeHash,
                       String requestIp, int maxAttempts, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO email_verification_codes
                    (id, email, purpose, code_hash, request_ip, max_attempts, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, email, purpose, codeHash, requestIp == null ? "" : requestIp,
                maxAttempts, JdbcTime.t(expiresAt));
    }

    /** 签发新码前令同邮箱同用途未消费旧码立即失效，确保仅最新验证码有效。 */
    public void invalidateUnconsumed(String email, String purpose, Instant now) {
        jdbc.update("""
                UPDATE email_verification_codes
                   SET consumed_at = ?
                 WHERE email = ? AND purpose = ? AND consumed_at IS NULL
                """, JdbcTime.t(now), email, purpose);
    }

    public Optional<VerificationCode> findLatestActive(String email, String purpose, Instant now) {
        return jdbc.query("""
                SELECT id, email, purpose, code_hash, request_ip, attempts, max_attempts,
                       expires_at, consumed_at, created_at
                  FROM email_verification_codes
                 WHERE email = ? AND purpose = ?
                   AND consumed_at IS NULL
                   AND expires_at > ?
                 ORDER BY created_at DESC
                 LIMIT 1
                """, mapper, email, purpose, JdbcTime.t(now)).stream().findFirst();
    }

    public Optional<Instant> findLatestCreatedAt(String email, String purpose) {
        return jdbc.query("""
                SELECT created_at
                  FROM email_verification_codes
                 WHERE email = ? AND purpose = ?
                 ORDER BY created_at DESC
                 LIMIT 1
                """, (rs, i) -> rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                email, purpose).stream().findFirst();
    }

    public long countSinceByEmail(String email, String purpose, Instant since) {
        Long n = jdbc.queryForObject("""
                SELECT count(*)
                  FROM email_verification_codes
                 WHERE email = ? AND purpose = ? AND created_at >= ?
                """, Long.class, email, purpose, JdbcTime.t(since));
        return n == null ? 0L : n;
    }

    public long countSinceByIp(String requestIp, Instant since) {
        if (requestIp == null || requestIp.isBlank()) return 0L;
        Long n = jdbc.queryForObject("""
                SELECT count(*)
                  FROM email_verification_codes
                 WHERE request_ip = ? AND created_at >= ?
                """, Long.class, requestIp, JdbcTime.t(since));
        return n == null ? 0L : n;
    }

    public void incrementAttempts(UUID id) {
        jdbc.update("UPDATE email_verification_codes SET attempts = attempts + 1 WHERE id = ?", id);
    }

    public void markConsumed(UUID id, Instant now) {
        jdbc.update("UPDATE email_verification_codes SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL",
                JdbcTime.t(now), id);
    }
}
