package com.mangashelf.external;

import java.time.Instant;
import java.util.UUID;

/** 外部集绑定（作品所有者操作；来源 ID 回链，DESIGN 17.2 模式）。解析结果短时效（M7）。 */
public record ExternalEpisode(
        UUID id,
        UUID workId,
        UUID sourceId,
        String title,
        String episodeUrl,
        int ordinal,
        Instant createdAt,
        String resolvedUrl,
        String resolvedKind,
        Instant resolvedAt) {
}
