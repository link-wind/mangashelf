package com.mangashelf.catalog;

import java.time.Instant;
import java.util.UUID;

/**
 * 章节版本（DESIGN 8.2 / 9）：DRAFT → PROCESSING → READY → PUBLISHED，或 FAILED；
 * 发布后清单不可原地改写，调序创建新版本。
 */
public record ChapterRevision(
        UUID id,
        UUID chapterId,
        int revisionNo,
        int editVersion,
        UUID sourceAssetId,
        String status,
        int pageCount,
        String manifestSha256,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_FAILED = "FAILED";
}
