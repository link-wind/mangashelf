package com.mangashelf.ingestion;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 写给 Python 任务的描述文件（DESIGN 12.2）：协议版本、job_id、attempt、输入清单、输出目录。
 * JSON 字段为 snake_case（Python 端协议约定），通过 @JsonProperty 显式映射。
 */
public record TaskSpec(
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("job_id") UUID jobId,
        int attempt,
        String task,
        List<TaskInput> inputs,
        @JsonProperty("output_dir") String outputDir) {

    public static final int SCHEMA_VERSION = 1;

    public record TaskInput(String role, @JsonProperty("path") String path) {
    }
}
