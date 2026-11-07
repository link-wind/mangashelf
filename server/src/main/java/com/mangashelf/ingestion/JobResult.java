package com.mangashelf.ingestion;

import java.util.UUID;

/** 任务结果与可选的链式任务（如 verify 成功后进入 process）。 */
public record JobResult(String status, String stage, Integer progress,
                        String errorCode, String errorMessage, ChainedJobSpec chain) {

    public record ChainedJobSpec(String kind, UUID ownerId, UUID uploadSessionId, String dedupKey) {
    }

    public static JobResult success(String stage) {
        return new JobResult(Job.STATUS_SUCCEEDED, stage, 100, null, null, null);
    }

    public static JobResult failure(String errorCode, String errorMessage) {
        return new JobResult(Job.STATUS_FAILED, errorCode, null, errorCode, errorMessage, null);
    }

    public JobResult withChain(ChainedJobSpec chain) {
        return new JobResult(status, stage, progress, errorCode, errorMessage, chain);
    }
}
