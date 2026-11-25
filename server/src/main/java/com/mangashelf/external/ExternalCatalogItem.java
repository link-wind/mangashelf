package com.mangashelf.external;

import java.time.Instant;
import java.util.UUID;

/** 资源目录条目（M8 D1）：资源站目录层，与个人书库分离；物化后回链 work_id。 */
public record ExternalCatalogItem(
        UUID id,
        UUID sourceId,
        long vodId,
        String name,
        String coverUrl,
        String remarks,
        int categoryId,
        String categoryName,
        String playFrom,
        String playGroups,
        int episodeCount,
        UUID workId,
        Long bangumiSubjectId,
        Instant resourceUpdatedAt,
        Instant syncedAt) {

    /** 返回封面重写后的副本（指向同源持久化封面缓存代理，避免前端直连慢外链）。 */
    public ExternalCatalogItem withCoverUrl(String rewrittenCoverUrl) {
        return new ExternalCatalogItem(
                id, sourceId, vodId, name, rewrittenCoverUrl, remarks, categoryId, categoryName,
                playFrom, playGroups, episodeCount, workId, bangumiSubjectId, resourceUpdatedAt, syncedAt);
    }
}
