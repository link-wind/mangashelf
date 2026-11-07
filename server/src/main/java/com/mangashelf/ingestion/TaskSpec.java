package com.mangashelf.ingestion;

import java.util.UUID;

/** 写给 Python 任务的描述文件（DESIGN 12.2）：协议版本、job_id、attempt、输入清单、输出目录。 */
public record TaskSpec(int schemaVersion, UUID jobId, int attempt, String task,
                       java.util.List<TaskInput> inputs, String outputDir) {

    public static final int SCHEMA_VERSION = 1;

    public record TaskInput(String role, String path) {
    }
}
