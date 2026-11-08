package com.mangashelf.catalog;

import java.time.Instant;
import java.util.UUID;

/** 作品容器（DESIGN 8.2 works）：可见性与对外状态分离（DESIGN 3.2 / 9）。 */
public record Work(
        UUID id,
        UUID ownerId,
        String title,
        String description,
        String visibility,
        String status,
        String readingDirection,
        Instant createdAt,
        Instant updatedAt) {

    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String VISIBILITY_PUBLIC = "PUBLIC";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
}
