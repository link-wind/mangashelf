package com.mangashelf.ingestion;

import java.time.Instant;
import java.util.UUID;

/**
 * 任务记录（DESIGN 8.2 / 11）。数据库记录是事实；队列消息只携带 job_id、dispatch_no、schema_version。
 * 目标外键按任务种类约束（数据库 CHECK）：content.verify/content.process → upload_session，
 * chapter.parse → chapter_revision。
 */
public record Job(
        UUID id,
        String kind,
        UUID ownerId,
        UUID uploadSessionId,
        UUID chapterRevisionId,
        UUID animationRevisionId,
        UUID indexGenerationId,
        UUID externalEpisodeId,
        String dedupKey,
        String status,
        String stage,
        Integer progress,
        int attempt,
        int maxAttempt,
        boolean cancelRequested,
        UUID leaseToken,
        Instant leaseUntil,
        Instant heartbeatAt,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public static final String KIND_VERIFY = "content.verify";
    public static final String KIND_PROCESS = "content.process";
    public static final String KIND_CHAPTER_PARSE = "chapter.parse";
    public static final String KIND_ANIMATION_VERIFY = "animation.verify";
    public static final String KIND_INDEX_BUILD = "index.build";
    public static final String KIND_EXTERNAL_RESOLVE = "external.resolve";

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}
