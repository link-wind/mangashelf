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
     * 目标外键按任务种类由数据库 CHECK（jobs_target_by_kind）约束。
     */
    public UUID insertOrExisting(String kind, UUID ownerId, UUID uploadSessionId,
                                 UUID chapterRevisionId, UUID animationRevisionId,
                                 UUID indexGenerationId, UUID externalEpisodeId, String dedupKey) {
        UUID id = UUID.randomUUID();
        List<UUID> inserted = jdbc.query("""
                INSERT INTO jobs (id, kind, owner_id, upload_session_id, chapter_revision_id,
                                  animation_revision_id, index_generation_id, external_episode_id, dedup_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (kind, dedup_key) DO NOTHING
                RETURNING id
                """, (rs, i) -> rs.getObject("id", UUID.class),
                id, kind, ownerId, uploadSessionId, chapterRevisionId, animationRevisionId,
                indexGenerationId, externalEpisodeId, dedupKey);
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

    public List<Job> findByChapterRevision(UUID chapterRevisionId) {
        return jdbc.query(
                "SELECT * FROM jobs WHERE chapter_revision_id = ? ORDER BY created_at", mapper, chapterRevisionId);
    }

    /** 取消标志（DESIGN 11.4）：Worker 在阶段边界检查；QUEUED/RUNNING 可取消。 */
    public boolean requestCancel(UUID jobId, UUID requesterId) {
        return jdbc.update("""
                UPDATE jobs SET cancel_requested = true, updated_at = now()
                WHERE id = ? AND owner_id = ? AND status IN ('QUEUED', 'RUNNING')
                """, jobId, requesterId) > 0;
    }

    public boolean isCancelRequested(UUID jobId) {
        List<Boolean> flags = jdbc.query(
                "SELECT cancel_requested FROM jobs WHERE id = ?",
                (rs, i) -> rs.getBoolean("cancel_requested"), jobId);
        return !flags.isEmpty() && flags.getFirst();
    }

    /** 显式重试（DESIGN 16.1）：FAILED → QUEUED，租约与取消标志复位；派发由调用方补发。 */
    public boolean resetForRetry(UUID jobId, UUID requesterId) {
        return jdbc.update("""
                UPDATE jobs SET status = 'QUEUED', stage = NULL, progress = NULL,
                       error_code = NULL, error_message = NULL, cancel_requested = false,
                       lease_token = NULL, lease_until = NULL, updated_at = now()
                WHERE id = ? AND owner_id = ? AND status = 'FAILED'
                """, jobId, requesterId) > 0;
    }

    /**
     * 恢复扫描（DESIGN 11.3）：过期租约任务在事务内判断 attempt 上限并重新入队；
     * 返回需要重派发的任务 id 列表。超限任务由调用方置 FAILED。
     */
    public List<UUID> claimExpiredLeases(int batch) {
        List<ExpiredLease> expired = jdbc.query("""
                SELECT id, attempt, max_attempt FROM jobs
                WHERE status = 'RUNNING' AND lease_until < now() AND cancel_requested = false
                ORDER BY lease_until
                LIMIT ?
                """, (rs, i) -> new ExpiredLease(rs.getObject("id", UUID.class),
                rs.getInt("attempt"), rs.getInt("max_attempt")), batch);
        return expired.stream()
                .map(e -> {
                    if (e.attempt() >= e.maxAttempt()) {
                        jdbc.update("""
                                UPDATE jobs SET status = 'FAILED', stage = 'LEASE_EXPIRED',
                                       error_code = 'LEASE_EXPIRED', error_message = '租约过期且重试已达上限',
                                       lease_token = NULL, lease_until = NULL, updated_at = now()
                                WHERE id = ? AND status = 'RUNNING' AND lease_until < now()
                                """, e.id());
                        appendEvent(e.id(), "result", "LEASE_EXPIRED", "租约过期且重试已达上限，等待显式重试");
                        return null;
                    }
                    int updated = jdbc.update("""
                                UPDATE jobs SET status = 'QUEUED', lease_token = NULL, lease_until = NULL,
                                       heartbeat_at = NULL, updated_at = now()
                                WHERE id = ? AND status = 'RUNNING' AND lease_until < now()
                                """, e.id());
                    return updated > 0 ? e.id() : null;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public record ExpiredLease(UUID id, int attempt, int maxAttempt) {
    }

    /** 重派发：追加 outbox 记录（dispatch_no 递增，DESIGN 11.3 恢复派发）。 */
    public int nextDispatchNo(UUID jobId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(dispatch_no), 0) FROM outbox_events WHERE job_id = ?",
                Integer.class, jobId);
        return (max == null ? 0 : max) + 1;
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

    /** SSE 补读（DESIGN 11.4）：sequence 大于游标的事件，按序号升序。 */
    public List<JobEvent> listEventsAfter(UUID jobId, long afterSequence) {
        return jdbc.query(
                "SELECT * FROM job_events WHERE job_id = ? AND sequence > ? ORDER BY sequence",
                eventMapper, jobId, afterSequence);
    }
}
