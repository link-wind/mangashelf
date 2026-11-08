package com.mangashelf.storage;

import java.time.Instant;
import java.util.UUID;

/** 上传会话（DESIGN 8.2 / 10.2）。状态机 PENDING → VERIFIED，或 FAILED / EXPIRED。 */
public record UploadSession(
        UUID id,
        UUID ownerId,
        UUID assetId,
        String purpose,
        String objectKey,
        Long expectedSize,
        String expectedSha256,
        String status,
        String idempotencyKey,
        String requestHash,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public static final String PURPOSE_MANGA_PAGE = "MANGA_PAGE";
    public static final String PURPOSE_ANIMATION_VIDEO = "ANIMATION_VIDEO";
    public static final String PURPOSE_ANIMATION_POSTER = "ANIMATION_POSTER";
    public static final String PURPOSE_ANIMATION_SUBTITLE = "ANIMATION_SUBTITLE";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_VERIFIED = "VERIFIED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_EXPIRED = "EXPIRED";
}
