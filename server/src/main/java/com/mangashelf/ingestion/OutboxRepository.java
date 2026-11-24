package com.mangashelf.ingestion;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 待派发记录（DESIGN 11.1）：与任务同事务写入；发布确认后才标记已派发；始终允许重复消息。
 * 领取带租约（harden-job-lease-and-routing / harden-reliable-jobs-and-fault-injection）：
 * 未过期租约互斥，发送期间持有令牌，确认与重试均按令牌更新，超时可被其他实例回收重派。
 */
@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<OutboxEvent> mapper = new DataClassRowMapper<>(OutboxEvent.class);

    public record OutboxEvent(long id, UUID jobId, int dispatchNo, String payload, String status,
                              int attempts, Instant nextAttemptAt, UUID leaseToken) {
    }

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 派发记录：新任务 dispatch_no=1；租约恢复重派发时递增（DESIGN 11.3）。 */
    public void insert(UUID jobId, int dispatchNo, String payload) {
        jdbc.update("""
                INSERT INTO outbox_events (job_id, dispatch_no, payload)
                VALUES (?, ?, ?::jsonb)
                """, jobId, dispatchNo, payload);
    }

    /** 延迟派发记录：用于白名单瞬时错误的指数退避重派。 */
    public void insertDelayed(UUID jobId, int dispatchNo, String payload, Duration delay) {
        double delaySeconds = Math.max(0.0, delay.toMillis() / 1000.0);
        jdbc.update("""
                INSERT INTO outbox_events (job_id, dispatch_no, payload, next_attempt_at)
                VALUES (?, ?, ?::jsonb, now() + make_interval(secs => ?))
                """, jobId, dispatchNo, payload, delaySeconds);
    }

    /**
     * 领取待派发记录（SKIP LOCKED 支持多实例并发）：
     * 仅领取未持有租约且到期（lease_token IS NULL AND next_attempt_at <= now()），
     * 或租约已过期（lease_token IS NOT NULL AND lease_until < now()）的 PENDING 记录。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimBatch(int limit, Duration lease) {
        double leaseSeconds = Math.max(0.001, lease.toMillis() / 1000.0);
        return jdbc.query("""
                UPDATE outbox_events
                   SET lease_token = ?, lease_until = now() + make_interval(secs => ?), updated_at = now()
                 WHERE id IN (
                       SELECT id FROM outbox_events
                        WHERE status = 'PENDING'
                          AND (
                                (lease_token IS NULL AND next_attempt_at <= now())
                             OR (lease_token IS NOT NULL AND lease_until < now())
                          )
                        ORDER BY id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                 )
                RETURNING id, job_id, dispatch_no, payload, status, attempts, next_attempt_at, lease_token
                """, mapper, UUID.randomUUID(), leaseSeconds, limit);
    }

    /** 按领取令牌确认；令牌不匹配（已被回收重派）时返回 false。 */
    public boolean markDispatched(long id, UUID leaseToken) {
        return jdbc.update("""
                UPDATE outbox_events SET status = 'DISPATCHED', lease_token = NULL, lease_until = NULL,
                       updated_at = now()
                 WHERE id = ? AND lease_token = ?
                """, id, leaseToken) > 0;
    }

    /**
     * 派发失败有限重试（按领取令牌条件更新，指数退避 + jitter）；超过上限标记 FAILED。
     * 令牌不匹配时返回 false，避免过期发送者误覆写新派发者的租约。
     */
    public boolean markRetry(long id, UUID leaseToken, int maxAttempts) {
        double jitterSeconds = ThreadLocalRandom.current().nextDouble(0.0, 2.0);
        return jdbc.update("""
                UPDATE outbox_events SET attempts = attempts + 1,
                       next_attempt_at = now() + make_interval(secs => LEAST(POWER(2, attempts), 30) + ?),
                       lease_token = NULL, lease_until = NULL,
                       status = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
                       updated_at = now()
                WHERE id = ? AND lease_token = ?
                """, jitterSeconds, maxAttempts, id, leaseToken) > 0;
    }

    /** 清理超过保留期的已派发历史记录，防止 outbox_events 无限增长。 */
    public int deleteDispatchedOlderThan(Duration retention) {
        double retentionSeconds = Math.max(1.0, retention.toMillis() / 1000.0);
        return jdbc.update("""
                DELETE FROM outbox_events
                 WHERE status = 'DISPATCHED'
                   AND updated_at < now() - make_interval(secs => ?)
                """, retentionSeconds);
    }

    /** 当前待派发数量（用于 Micrometer Gauge）。 */
    public long countPending() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'", Long.class);
        return count == null ? 0L : count;
    }

    /** 最老一条 PENDING 记录的滞留秒数（无待派发记录时为 0）。 */
    public double oldestPendingAgeSeconds() {
        Double age = jdbc.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0)
                  FROM outbox_events
                 WHERE status = 'PENDING'
                """, Double.class);
        return age == null || age < 0 ? 0.0 : age;
    }
}
