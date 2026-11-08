package com.mangashelf.animation;

import java.time.Instant;
import java.util.UUID;

/** 动画容器（DESIGN 8.2 animations）：可见性与对外状态分离（DESIGN 3.2 / 9）。 */
public record Animation(
        UUID id,
        UUID ownerId,
        String title,
        String description,
        String visibility,
        String status,
        UUID currentRevisionId,
        Instant createdAt,
        Instant updatedAt) {

    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String VISIBILITY_PUBLIC = "PUBLIC";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
}
