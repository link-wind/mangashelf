package com.mangashelf.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * 对象元数据（DESIGN 8.2 assets）。kind / state 由数据库 CHECK 约束限定；
 * upload_session_id 是 M0 的临时来源关联（派生产物经 metadata jsonb），M1 由 pages 表取代。
 */
public record Asset(
        UUID id,
        UUID ownerId,
        String objectKey,
        String kind,
        String state,
        Long sizeBytes,
        String declaredSha256,
        String verifiedSha256,
        String contentType,
        UUID uploadSessionId,
        Instant createdAt,
        Instant updatedAt) {

    public static final String KIND_ORIGINAL = "ORIGINAL";
    public static final String KIND_READER_IMAGE = "READER_IMAGE";
    public static final String KIND_THUMBNAIL = "THUMBNAIL";
    public static final String KIND_VIDEO = "VIDEO";
    public static final String KIND_POSTER = "POSTER";
    public static final String KIND_SUBTITLE = "SUBTITLE";

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_VERIFIED = "VERIFIED";
    public static final String STATE_FAILED = "FAILED";
}
