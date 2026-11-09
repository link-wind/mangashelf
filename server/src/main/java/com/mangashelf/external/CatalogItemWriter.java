package com.mangashelf.external;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 资源站条目落目录层的共用写入（M8 同步 / M9 反查共用）。
 * play_groups 保留线路原文 JSON 数组：[{from, raw}]。
 */
@Component
public class CatalogItemWriter {

    private final ExternalCatalogRepository catalogItems;
    private final ObjectMapper objectMapper;

    public CatalogItemWriter(ExternalCatalogRepository catalogItems, ObjectMapper objectMapper) {
        this.catalogItems = catalogItems;
        this.objectMapper = objectMapper;
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
        catalogItems.upsert(sourceId, v.path("vod_id").asLong(), v.path("vod_name").asText(),
                v.path("vod_pic").asText(""), v.path("vod_remarks").asText(""),
                v.path("type_id").asInt(), v.path("type_name").asText(""),
                playFrom, groups.toString(), episodeCount, parseTime(v.path("vod_time").asText("")));
        return episodeCount;
    }

    private static Instant parseTime(String s) {
        try {
            return LocalDateTime.parse(s.replace(" ", "T")).atOffset(ZoneOffset.ofHours(8)).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
