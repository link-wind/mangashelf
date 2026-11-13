package com.mangashelf.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 待派发记录（DESIGN 11.1）：与任务同事务写入；发布确认后才标记已派发；始终允许重复消息。
 * 领取带租约（harden-job-lease-and-routing）：发送期间持有令牌，确认按令牌更新，
 * 超时未确认的记录可被其他实例回收重派。
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

    /**
     * 领取待派发记录（SKIP LOCKED 支持多实例并发）：
     * 候选为 PENDING 且到期，或曾领取但租约已过期（发送实例可能已崩溃）。
     * 领取即写入新令牌与期限，行锁随短事务释放，后续更新按令牌校验。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimBatch(int limit, java.time.Duration lease) {
        return jdbc.query("""
                UPDATE outbox_events
                   SET lease_token = ?, lease_until = now() + make_interval(secs => ?), updated_at = now()
                 WHERE id IN (
                       SELECT id FROM outbox_events
                        WHERE status = 'PENDING' AND next_attempt_at <= now()
                           OR (status = 'PENDING' AND lease_token IS NOT NULL AND lease_until < now())
                        ORDER BY id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                 )
                RETURNING id, job_id, dispatch_no, payload, status, attempts, next_attempt_at, lease_token
                """, mapper, UUID.randomUUID(), lease.toSeconds(), limit);
    }

    /** 按领取令牌确认；令牌不匹配（已被回收重派）时返回 false。 */
    public boolean markDispatched(long id, UUID leaseToken) {
        return jdbc.update("""
                UPDATE outbox_events SET status = 'DISPATCHED', lease_token = NULL, lease_until = NULL,
                       updated_at = now()
                 WHERE id = ? AND lease_token = ?
                """, id, leaseToken) > 0;
    }

    /** 派发失败有限重试；超过上限标记 FAILED 等待人工处理（DESIGN 11.3）。 */
    public void markRetry(long id, int maxAttempts) {
        jdbc.update("""
                UPDATE outbox_events SET attempts = attempts + 1,
                       next_attempt_at = now() + make_interval(secs => LEAST(attempts * 2, 30)),
                       lease_token = NULL, lease_until = NULL,
                       status = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
                       updated_at = now()
                WHERE id = ?
                """, maxAttempts, id);
    }
}
