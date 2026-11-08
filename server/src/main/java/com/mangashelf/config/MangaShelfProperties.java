package com.mangashelf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

/** M0 配置（对齐根目录 .env.example）；M1 引入身份、配额后扩展。 */
@ConfigurationProperties(prefix = "mangashelf")
public record MangaShelfProperties(
        UUID demoOwnerId,
        String platformId,
        Storage storage,
        Jobs jobs,
        Processing processing,
        Embedding embedding) {

    public record Embedding(boolean enabled, String modelPath, String tokenizerPath) {
    }

    public record Storage(
            String endpoint,
            String region,
            String bucket,
            String accessKey,
            String secretKey,
            boolean pathStyle,
            int presignTtlSeconds) {
    }

    public record Jobs(
            long leaseSeconds,
            long heartbeatSeconds,
            long dispatchIntervalMs,
            int maxDispatchAttempts) {
    }

    public record Processing(
            String pythonBinary,
            String packageRoot,
            String workRoot,
            int taskTimeoutSeconds,
            int archiveMaxEntries,
            long archiveMaxTotalBytes,
            long archiveMaxFileBytes) {
    }
}
