package com.mangashelf.ingestion;

/** 固定任务清单，不开放任意脚本执行（DESIGN 12.2）。 */
public interface JobHandler {

    /** 任务种类，如 {@code content.verify}；与 jobs.kind 的 CHECK 约束一致。 */
    String kind();

    JobResult run(JobContext ctx) throws Exception;
}
