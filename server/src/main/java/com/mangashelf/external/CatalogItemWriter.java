package com.mangashelf.external;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 资源站条目落目录层的共用写入（M8 同步 / M9 反查共用）。
 * play_groups 保留线路原文 JSON 数组：[{from, raw}]。
 * 当条目已物化入书库（work_id 非空）或已有浏览态剧集记录时，自动将新集数幂等追加到 external_episodes。
 */
@Component
public class CatalogItemWriter {

    private final ExternalCatalogRepository catalogItems;
    private final ExternalRepository externals;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Autowired
    public CatalogItemWriter(ExternalCatalogRepository catalogItems, ExternalRepository externals,
                             JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.catalogItems = catalogItems;
        this.externals = externals;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    CatalogItemWriter(ExternalCatalogRepository catalogItems, ObjectMapper objectMapper) {
        this(catalogItems, null, null, objectMapper);
    }

    /** 写入一条 MacCMS vod 条目，返回条目集数（用于统计）。 */
    public int upsertVod(UUID sourceId, JsonNode v) {
        String playFrom = v.path("vod_play_from").asText("");
        String playUrl = v.path("vod_play_url").asText("");
        var groups = objectMapper.createArrayNode();
        String[] froms = playFrom.split("\\$\\$\\$");
        String[] urlGroups = playUrl.split("\\$\\$\\$");
        int episodeCount = 0;
        for (int i = 0; i < urlGroups.length; i++) {
            var g = objectMapper.createObjectNode();
            g.put("from", i < froms.length ? froms[i] : "line" + i);
            g.put("raw", urlGroups[i]);
            groups.add(g);
            episodeCount = Math.max(episodeCount, urlGroups[i].split("#").length);
        }
        String groupsJson = groups.toString();
        String remarks = v.path("vod_remarks").asText("");
        var upserted = catalogItems.upsert(sourceId, v.path("vod_id").asLong(), v.path("vod_name").asText(),
                v.path("vod_pic").asText(""), remarks,
                v.path("type_id").asInt(), v.path("type_name").asText(""),
                playFrom, groupsJson, episodeCount, parseTime(v.path("vod_time").asText("")));
        if (externals != null && upserted != null) {
            syncExistingEpisodesIfNeeded(sourceId, upserted, groupsJson, remarks);
        }
        return episodeCount;
    }

    private void syncExistingEpisodesIfNeeded(UUID sourceId, ExternalCatalogRepository.UpsertedItem item,
                                              String groupsJson, String remarks) {
        try {
            if (item.workId() != null) {
                List<ExternalCatalogService.Episode> parsed =
                        ExternalCatalogService.parsePlayGroups(objectMapper, groupsJson);
                for (var ep : parsed) {
                    externals.insertEpisode(UUID.randomUUID(), item.workId(), sourceId,
                            ep.title(), ep.url(), ep.ordinal(), ep.lineName(), ep.lineRank(),
                            ep.episodeType());
                }
                if (jdbc != null && !remarks.isBlank()) {
                    jdbc.update("UPDATE works SET description = ?, updated_at = now() WHERE id = ?",
                            remarks, item.workId());
                }
            } else if (externals.hasCatalogEpisodes(item.id())) {
                List<ExternalCatalogService.Episode> parsed =
                        ExternalCatalogService.parsePlayGroups(objectMapper, groupsJson);
                for (var ep : parsed) {
                    externals.insertCatalogEpisode(UUID.randomUUID(), item.id(), sourceId,
                            ep.title(), ep.url(), ep.ordinal(), ep.lineName(), ep.lineRank(),
                            ep.episodeType());
                }
            }
        } catch (Exception ignored) {
            // 单条剧集追更异常不中断目录整体同步
        }
    }

    private static Instant parseTime(String s) {
        try {
            return LocalDateTime.parse(s.replace(" ", "T")).atOffset(ZoneOffset.ofHours(8)).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
