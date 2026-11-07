package com.mangashelf.storage;

import java.util.UUID;

/** 对象 Key 由服务端生成（DESIGN 18.1）；Key 本身不是授权机制。 */
public final class StorageKeys {

    private StorageKeys() {
    }

    public static String uploadOriginal(UUID ownerId, UUID uploadId) {
        return "users/%s/uploads/%s/original".formatted(ownerId, uploadId);
    }

    /** 任务产物写入任务专属位置，attempt 隔离避免失败覆盖（DESIGN 10.3 / 18.2）。 */
    public static String jobArtifact(UUID jobId, int attempt, String artifactName) {
        return "jobs/%s/attempt-%d/%s".formatted(jobId, attempt, artifactName);
    }
}
