package com.mangashelf.ingestion;

import com.mangashelf.config.MangaShelfProperties;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 租约恢复扫描（DESIGN 11.3）：过期租约在条件更新内判断 attempt 上限；
 * 未超限重新入队并追加 outbox 派发记录，超限置 FAILED 等待显式重跑。
 * API 与 Worker 均可运行；条件更新保证并发安全。
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
    public void recoverExpiredLeases() {
        List<UUID> toRedispatch = jobs.claimExpiredLeases(50);
        for (UUID jobId : toRedispatch) {
            int dispatchNo = jobs.nextDispatchNo(jobId);
            try {
                String payload = objectMapper.writeValueAsString(
                        new JobMessage(JobMessage.SCHEMA_VERSION, jobId, dispatchNo));
                outbox.insert(jobId, dispatchNo, payload);
                jobs.appendEvent(jobId, "progress", "recover", "租约过期，重新派发（dispatch " + dispatchNo + "）");
                log.info("job {} lease expired, re-dispatched as dispatch {}", jobId, dispatchNo);
            } catch (Exception e) {
                log.error("failed to re-dispatch job {}", jobId, e);
            }
        }
    }
}
