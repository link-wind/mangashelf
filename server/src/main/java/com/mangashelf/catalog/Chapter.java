package com.mangashelf.catalog;

import java.time.Instant;
import java.util.UUID;

/** 章节（DESIGN 8.2 chapters）：顺序唯一，current_revision 归属由组合外键保证。 */
public record Chapter(
        UUID id,
        UUID workId,
        int ordinal,
        String title,
        UUID currentRevisionId,
        Instant createdAt,
        Instant updatedAt) {
}
