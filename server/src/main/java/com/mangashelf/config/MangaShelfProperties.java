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
        Bangumi bangumi,
        Redis redis) {

    /** Bangumi 元数据源（M9）：api.bgm.tv 官方接口；proxy 供网络受限环境选配。 */
    public record Bangumi(boolean enabled, String proxy) {
    }

    /** 共享缓存（redis-shared-cache-and-storage）：可开关；故障时降级为无缓存。 */
    public record Redis(boolean enabled, String address, int commandTimeoutMs, int connectTimeoutMs) {
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
            int maxDispatchAttempts,
            /** Worker 声明消费的队列（harden-job-lease-and-routing）；空列表不消费任何队列。 */
            java.util.List<String> consumeQueues) {
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
