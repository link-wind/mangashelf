package com.mangashelf.ingestion;

import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** 任务 + 待派发记录的幂等创建（事务内，DESIGN 11.1）；各业务模块共用。 */
@Service
public class JobCreationService {

    private final JobRepository jobs;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public JobCreationService(JobRepository jobs, OutboxRepository outbox,
                              ObjectMapper objectMapper, TransactionTemplate tx) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.tx = tx;
    }

    /** 幂等：同 (kind, dedup_key) 返回既有任务。目标按任务种类由数据库 CHECK 约束。 */
    public UUID create(String kind, UUID ownerId, UUID uploadSessionId,
                       UUID chapterRevisionId, UUID animationRevisionId,
                       UUID indexGenerationId, UUID externalEpisodeId,
                       UUID syncSourceId, String paramsJson, String dedupKey) {
        return tx.execute(status -> {
            UUID jobId = jobs.insertOrExisting(kind, ownerId, uploadSessionId, chapterRevisionId,
                    animationRevisionId, indexGenerationId, externalEpisodeId,
                    syncSourceId, paramsJson, dedupKey);
            outbox.insert(jobId, jobs.nextDispatchNo(jobId), payloadOf(jobId));
            return jobId;
        });
    }

    private String payloadOf(UUID jobId) {
        try {
            return objectMapper.writeValueAsString(new JobMessage(JobMessage.SCHEMA_VERSION, jobId, 1));
        } catch (Exception e) {
            throw new IllegalStateException("payload serialization failed", e);
        }
    }
}
