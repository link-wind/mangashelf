package com.mangashelf.ingestion;

/** 任务结果（M1：无链式派发；解析由所有者显式创建修订触发）。 */
public record JobResult(String status, String stage, Integer progress,
                        String errorCode, String errorMessage) {

    public static JobResult success(String stage) {
        return new JobResult(Job.STATUS_SUCCEEDED, stage, 100, null, null);
    }

    public static JobResult failure(String errorCode, String errorMessage) {
        return new JobResult(Job.STATUS_FAILED, errorCode, null, errorCode, errorMessage);
    }
}
