package com.mangashelf.animation;

import java.time.Instant;
import java.util.UUID;

/**
 * 动画版本（DESIGN 8.2 / 9）：DRAFT → VERIFYING → READY → PUBLISHED，或 FAILED。
 * 媒体校验通过后才能 READY；来源清单在 verify 时绑定、publish 时重校验。
 */
public record AnimationRevision(
        UUID id,
        UUID animationId,
        int revisionNo,
        int editVersion,
        String status,
        UUID videoAssetId,
        UUID posterAssetId,
        UUID subtitleAssetId,
        Long durationMs,
        Integer videoWidth,
        Integer videoHeight,
        String videoCodec,
        String audioCodec,
        Boolean hasAudio,
        String sourceManifestSha256,
        String errorCode,
        String errorMessage,
        String originClient,
        String originRunRef,
        Instant createdAt,
        Instant updatedAt) {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_VERIFYING = "VERIFYING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_FAILED = "FAILED";
}
