package com.mangashelf.ingestion;

import com.mangashelf.config.MangaShelfProperties;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 租约恢复扫描（DESIGN 11.3 / harden-job-lease-and-routing 决策 2）：
 * 状态置回、outbox 记录与恢复事件在同一事务内提交——中途崩溃不会留下
 * "QUEUED 但无 outbox 记录"的孤儿任务；attempt 上限在同一事务内判断，
 * 超限任务置 FAILED 等待显式重跑。API 与 Worker 均可运行（条件更新并发安全）。
 */
@Component
public class LeaseRecoveryScanner {

    private static final Logger log = LoggerFactory.getLogger(LeaseRecoveryScanner.class);

    private final JobRepository jobs;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final MangaShelfProperties props;

    public LeaseRecoveryScanner(JobRepository jobs, OutboxRepository outbox,
                                ObjectMapper objectMapper, MangaShelfProperties props) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${mangashelf.jobs.recovery-interval-ms:10000}")
    @Transactional
    public void recoverExpiredLeases() {
        List<JobRepository.ExpiredLease> expired = jobs.claimExpiredLeases(50);
        for (JobRepository.ExpiredLease lease : expired) {
            try {
                int dispatchNo = jobs.nextDispatchNo(lease.id());
                String payload = objectMapper.writeValueAsString(
                        new JobMessage(JobMessage.SCHEMA_VERSION, lease.id(), dispatchNo, lease.kind()));
                outbox.insert(lease.id(), dispatchNo, payload);
                jobs.appendEvent(lease.id(), "progress", "recover",
                        "租约过期，重新派发（dispatch " + dispatchNo + "）");
                log.info("job {} lease expired, re-dispatched as dispatch {}", lease.id(), dispatchNo);
            } catch (Exception e) {
                log.error("failed to re-dispatch job {}", lease.id(), e);
                // 单个任务失败回滚整体事务会让其他恢复也丢失；标记后交给下一轮扫描重试。
                throw (e instanceof RuntimeException re) ? re : new IllegalStateException(e);
            }
        }
    }
}
