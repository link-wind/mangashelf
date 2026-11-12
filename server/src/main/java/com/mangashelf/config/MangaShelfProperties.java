package com.mangashelf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

/** M0 配置（对齐根目录 .env.example）；M1 引入身份、配额后扩展。 */
@ConfigurationProperties(prefix = "mangashelf")
public record MangaShelfProperties(
        UUID demoOwnerId,
        String platformId,
        String adminPassword,
        Storage storage,
        Jobs jobs,
        Processing processing,
        Embedding embedding,
        Bangumi bangumi) {

    /** Bangumi 元数据源（M9）：api.bgm.tv 官方接口；proxy 供网络受限环境选配。 */
    public record Bangumi(boolean enabled, String proxy) {
    }

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
