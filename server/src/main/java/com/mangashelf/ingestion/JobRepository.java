package com.mangashelf.ingestion;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JobRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<Job> mapper = new DataClassRowMapper<>(Job.class);
    private final RowMapper<JobEvent> eventMapper = new DataClassRowMapper<>(JobEvent.class);

    public JobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等创建：同 (kind, dedup_key) 返回既有任务（DESIGN 16.2 幂等键语义）。
     */
    public UUID insertOrExisting(String kind, UUID ownerId, UUID uploadSessionId, String dedupKey) {
        UUID id = UUID.randomUUID();
        List<UUID> inserted = jdbc.query("""
                INSERT INTO jobs (id, kind, owner_id, upload_session_id, dedup_key)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (kind, dedup_key) DO NOTHING
                RETURNING id
                """, (rs, i) -> rs.getObject("id", UUID.class),
                id, kind, ownerId, uploadSessionId, dedupKey);
        if (!inserted.isEmpty()) {
            return inserted.getFirst();
        }
        return jdbc.queryForObject(
                "SELECT id FROM jobs WHERE kind = ? AND dedup_key = ?", UUID.class, kind, dedupKey);
    }

    public Optional<Job> findById(UUID id) {
        return jdbc.query("SELECT * FROM jobs WHERE id = ?", mapper, id).stream().findFirst();
    }

    public List<Job> findByUploadSession(UUID uploadSessionId) {
        return jdbc.query(
                "SELECT * FROM jobs WHERE upload_session_id = ? ORDER BY created_at", mapper, uploadSessionId);
    }

    public record ClaimResult(Outcome outcome, Job job, UUID leaseToken) {
        public enum Outcome { CLAIMED, FINISHED, ACTIVE_LEASE, EXPIRED_LEASE }
    }

    /**
     * 条件更新取得租约（DESIGN 11.2）：仅 QUEUED 可被领取；重复通知读到终态或有效租约时不执行。
     */
    public ClaimResult tryClaim(UUID jobId, long leaseSeconds) {
        UUID token = UUID.randomUUID();
        List<Job> claimed = jdbc.query("""
                UPDATE jobs SET status = 'RUNNING', attempt = attempt + 1,
                       lease_token = ?, lease_until = now() + make_interval(secs => ?),
                       heartbeat_at = now(), updated_at = now()
                WHERE id = ? AND status = 'QUEUED'
                RETURNING *
                """, mapper, token, leaseSeconds, jobId);
        if (!claimed.isEmpty()) {
            return new ClaimResult(ClaimResult.Outcome.CLAIMED, claimed.getFirst(), token);
        }
        Job current = findById(jobId).orElseThrow(() -> new IllegalStateException("job missing: " + jobId));
        return switch (current.status()) {
            case Job.STATUS_SUCCEEDED, Job.STATUS_FAILED, Job.STATUS_CANCELLED ->
                    new ClaimResult(ClaimResult.Outcome.FINISHED, current, null);
            case Job.STATUS_RUNNING -> current.leaseUntil() != null && current.leaseUntil().isAfter(java.time.Instant.now())
                    ? new ClaimResult(ClaimResult.Outcome.ACTIVE_LEASE, current, null)
                    : new ClaimResult(ClaimResult.Outcome.EXPIRED_LEASE, current, null);
            default -> new ClaimResult(ClaimResult.Outcome.ACTIVE_LEASE, current, null);
        };
    }

    /** 心跳续约；返回 false 表示租约已失效。 */
    public boolean heartbeat(UUID jobId, UUID leaseToken, long leaseSeconds, Integer progress) {
        return jdbc.update("""
                UPDATE jobs SET heartbeat_at = now(), lease_until = now() + make_interval(secs => ?),
                       progress = COALESCE(?, progress), updated_at = now()
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                """, leaseSeconds, progress, jobId, leaseToken) > 0;
    }

    /**
     * 提交结果须同时匹配状态与租约令牌（DESIGN 11.2 规则 5）；过期 Worker 不能覆盖新 attempt。
     */
    public boolean commitResult(UUID jobId, UUID leaseToken, String status, String stage,
                                Integer progress, String errorCode, String errorMessage) {
        return jdbc.update("""
                UPDATE jobs SET status = ?, stage = ?, progress = ?, error_code = ?, error_message = ?,
                       lease_token = NULL, lease_until = NULL, updated_at = now()
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                """, status, stage, progress, errorCode, errorMessage, jobId, leaseToken) > 0;
    }

    public void appendEvent(UUID jobId, String type, String stage, String message) {
        jdbc.update("""
                INSERT INTO job_events (job_id, sequence, type, stage, message)
                SELECT ?, COALESCE(MAX(sequence), 0) + 1, ?, ?, ? FROM job_events WHERE job_id = ?
                """, jobId, type, stage, message, jobId);
    }

    public List<JobEvent> listEvents(UUID jobId) {
        return jdbc.query(
                "SELECT * FROM job_events WHERE job_id = ? ORDER BY sequence", eventMapper, jobId);
    }
}
