package com.mangashelf.ingestion;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 写给 Python 任务的描述文件（DESIGN 12.2）：协议版本、job_id、attempt、输入清单、输出目录。
 * JSON 字段为 snake_case（Python 端协议约定），通过 @JsonProperty 显式映射。
 * limits 由 Java 配置下发，Python 不读取环境之外的策略。
 */
public record TaskSpec(
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("job_id") UUID jobId,
        int attempt,
        String task,
        List<TaskInput> inputs,
        @JsonProperty("output_dir") String outputDir,
        Limits limits,
        @JsonProperty("params") java.util.Map<String, Object> params) {

    public static final int SCHEMA_VERSION = 1;

    public TaskSpec(int schemaVersion, UUID jobId, int attempt, String task,
                    List<TaskInput> inputs, String outputDir, Limits limits) {
        this(schemaVersion, jobId, attempt, task, inputs, outputDir, limits, null);
    }

    public record TaskInput(String role, @JsonProperty("path") String path,
                            @JsonProperty("ordinal") Integer ordinal) {

        public TaskInput(String role, String path) {
            this(role, path, null);
        }
    }

    /** 解析防护上限（DESIGN 10.3），来源为服务端配置而非用户声明。 */
    public record Limits(
            @JsonProperty("max_entries") int maxEntries,
            @JsonProperty("max_total_bytes") long maxTotalBytes,
            @JsonProperty("max_file_bytes") long maxFileBytes) {
    }
}
