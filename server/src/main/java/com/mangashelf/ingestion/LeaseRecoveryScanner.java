package com.mangashelf.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 租约恢复扫描（DESIGN 11.3 / harden-reliable-jobs-and-fault-injection）：
 * 每条过期任务在独立短事务内完成「状态置回 + outbox 写入 + 恢复事件」原子提交，
 * 单条任务失败不会回滚同批次其他任务的恢复。API 与 Worker 均可运行（条件更新并发安全）。
 */
@Component
public class LeaseRecoveryScanner {

    private static final Logger log = LoggerFactory.getLogger(LeaseRecoveryScanner.class);

    private final JobRepository jobs;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final JobMetrics metrics;

    public LeaseRecoveryScanner(JobRepository jobs, OutboxRepository outbox,
                                ObjectMapper objectMapper, TransactionTemplate tx,
                                JobMetrics metrics) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.tx = tx;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${mangashelf.jobs.recovery-interval-ms:10000}")
    public void recoverExpiredLeases() {
        recoverBatch(50);
    }

    /** 返回本轮成功恢复重派的任务数量（便于集成测试断言）。 */
    public int recoverBatch(int limit) {
        List<JobRepository.ExpiredLease> expired = jobs.findExpiredLeases(limit);
        int requeued = 0;
        for (JobRepository.ExpiredLease lease : expired) {
            try {
                JobRepository.RecoveryOutcome outcome = tx.execute(status -> {
                    JobRepository.RecoveryOutcome res = jobs.recoverSingleExpiredLease(lease);
                    if (res == JobRepository.RecoveryOutcome.REQUEUED) {
                        int dispatchNo = jobs.nextDispatchNo(lease.id());
                        String payload;
                        try {
                            payload = objectMapper.writeValueAsString(
                                    new JobMessage(JobMessage.SCHEMA_VERSION, lease.id(), dispatchNo, lease.kind()));
                        } catch (Exception e) {
                            throw new IllegalStateException("recover payload serialization failed", e);
                        }
                        outbox.insert(lease.id(), dispatchNo, payload);
                        jobs.appendEvent(lease.id(), "progress", "recover",
                                "租约过期，重新派发（dispatch " + dispatchNo + "）");
                    }
                    return res;
                });
                if (outcome == JobRepository.RecoveryOutcome.REQUEUED) {
                    requeued++;
                    metrics.recordLeaseRecovered();
                    log.info("job {} lease expired, re-dispatched", lease.id());
                } else if (outcome == JobRepository.RecoveryOutcome.EXHAUSTED) {
                    metrics.recordLeaseRecovered();
                    log.warn("job {} lease expired and reached max_attempt={}", lease.id(), lease.maxAttempt());
                }
            } catch (Exception e) {
                log.error("failed to recover expired lease for job {}, skipping without rolling back batch",
                        lease.id(), e);
            }
        }
        return requeued;
    }
}
