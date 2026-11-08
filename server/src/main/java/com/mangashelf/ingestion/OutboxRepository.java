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

/** 待派发记录（DESIGN 11.1）：与任务同事务写入；发布确认后才标记已派发；始终允许重复消息。 */
@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<OutboxEvent> mapper = new DataClassRowMapper<>(OutboxEvent.class);

    public record OutboxEvent(long id, UUID jobId, int dispatchNo, String payload, String status,
                              int attempts, Instant nextAttemptAt) {
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

    /** SKIP LOCKED 支持多角色并发派发而不重复领取。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimBatch(int limit) {
        return jdbc.query("""
                SELECT * FROM outbox_events
                WHERE status = 'PENDING' AND next_attempt_at <= now()
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, mapper, limit);
    }

    public void markDispatched(long id) {
        jdbc.update("UPDATE outbox_events SET status = 'DISPATCHED', updated_at = now() WHERE id = ?", id);
    }

    /** 派发失败有限重试；超过上限标记 FAILED 等待人工处理（DESIGN 11.3）。 */
    public void markRetry(long id, int maxAttempts) {
        jdbc.update("""
                UPDATE outbox_events SET attempts = attempts + 1,
                       next_attempt_at = now() + make_interval(secs => LEAST(attempts * 2, 30)),
                       status = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
                       updated_at = now()
                WHERE id = ?
                """, maxAttempts, id);
    }
}
