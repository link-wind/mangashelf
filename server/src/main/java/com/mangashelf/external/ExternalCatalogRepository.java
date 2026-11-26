package com.mangashelf.external;

import com.mangashelf.config.JdbcTime;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 资源目录仓储（M8 D1/D2/D3）：显式 SQL，(source_id, vod_id) 幂等。 */
@Repository
public class ExternalCatalogRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<ExternalCatalogItem> mapper = new DataClassRowMapper<>(ExternalCatalogItem.class);

    public ExternalCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record UpsertedItem(UUID id, UUID workId) {}

    /** 单条 upsert（playGroups 为线路组原文 JSON 数组字符串），返回条目 ID 与已物化作品 ID。 */
    public UpsertedItem upsert(UUID sourceId, long vodId, String name, String coverUrl, String remarks,
                               int categoryId, String categoryName, String playFrom, String playGroups,
                               int episodeCount, Instant resourceUpdatedAt) {
        return upsert(sourceId, vodId, name, coverUrl, remarks, categoryId, categoryName,
                playFrom, playGroups, episodeCount, resourceUpdatedAt, List.of(), null);
    }

    /** 单条 upsert（含原生题材标签与上映年份）。 */
    public UpsertedItem upsert(UUID sourceId, long vodId, String name, String coverUrl, String remarks,
                               int categoryId, String categoryName, String playFrom, String playGroups,
                               int episodeCount, Instant resourceUpdatedAt,
                               List<String> tags, Integer releaseYear) {
        List<String> cleanTags = TagNormalizer.normalize(tags);
        return jdbc.queryForObject("""
                INSERT INTO external_catalog_items
                    (id, source_id, vod_id, name, cover_url, remarks, category_id, category_name,
                     play_from, play_groups, episode_count, resource_updated_at, tags, release_year)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (source_id, vod_id) DO UPDATE SET
                    name = EXCLUDED.name, cover_url = EXCLUDED.cover_url, remarks = EXCLUDED.remarks,
                    category_id = EXCLUDED.category_id, category_name = EXCLUDED.category_name,
                    play_from = EXCLUDED.play_from, play_groups = EXCLUDED.play_groups,
                    episode_count = EXCLUDED.episode_count,
                    resource_updated_at = EXCLUDED.resource_updated_at,
                    tags = CASE WHEN cardinality(EXCLUDED.tags) > 0 THEN EXCLUDED.tags ELSE external_catalog_items.tags END,
                    release_year = COALESCE(EXCLUDED.release_year, external_catalog_items.release_year),
                    synced_at = now()
                RETURNING id, work_id
                """,
                (rs, i) -> new UpsertedItem(
                        rs.getObject("id", UUID.class),
                        rs.getObject("work_id", UUID.class)),
                UUID.randomUUID(), sourceId, vodId, name, coverUrl, remarks, categoryId,
                categoryName, playFrom, playGroups, episodeCount, JdbcTime.t(resourceUpdatedAt),
                cleanTags.toArray(new String[0]), releaseYear);
    }

    /** 列表页精简投影：跳过平均 ~20KB/行的 play_groups 巨型 JSONB（详情接口 findItem 仍返回完整列）。 */
    private static final String LIST_COLUMNS = """
            e.id, e.source_id, e.vod_id, e.name, e.cover_url, e.remarks, e.category_id, e.category_name,
            e.play_from, '' AS play_groups, e.episode_count, e.work_id, e.bangumi_subject_id,
            e.resource_updated_at, e.synced_at
            """;

    /**
     * 目录分页（benchmark-db-and-hotspot-cache + revamp-discovery-search-index-and-nav）：
     * - 精简列投影消除列表页巨型 JSONB 传输与序列化放大；
     * - genre 使用 tags @> ARRAY[?] 命中 bangumi_subjects GIN 索引；
     * - 支持 year 筛选、rating/episodes/updated 排序以及统一的偏移页码分页（保留旧游标兼容）。
     */
    public List<ExternalCatalogItem> page(UUID sourceId, Integer categoryId, String q,
                                          String status, String sort, String genre, Boolean season,
                                          Instant beforeUpdated, UUID beforeId,
                                          int offset, int limit) {
        return page(sourceId, categoryId, q, status, sort, genre, season, null,
                beforeUpdated, beforeId, "episodes".equals(sort) ? offset : null, limit);
    }

    public List<ExternalCatalogItem> page(UUID sourceId, Integer categoryId, String q,
                                          String status, String sort, String genre, Boolean season,
                                          String year, Instant beforeUpdated, UUID beforeId,
                                          Integer offset, int limit) {
        StringBuilder where = new StringBuilder(" WHERE TRUE ");
        List<Object> args = new java.util.ArrayList<>();
        appendFilters(where, args, sourceId, categoryId, q, status, genre, season, year);

        boolean byRating = "rating".equals(sort);
        boolean byEpisodes = "episodes".equals(sort);
        if (byRating) {
            args.add(Math.max(offset == null ? 0 : offset, 0));
            args.add(limit);
            return jdbc.query("""
                    SELECT %s
                      FROM external_catalog_items e
                      LEFT JOIN bangumi_subjects b ON b.subject_id = e.bangumi_subject_id
                    %s
                     ORDER BY b.rating DESC NULLS LAST,
                              COALESCE(e.resource_updated_at, e.synced_at) DESC,
                              e.id DESC
                    OFFSET ? LIMIT ?
                    """.formatted(LIST_COLUMNS, where), mapper, args.toArray());
        }
        if (byEpisodes || offset != null) {
            String orderBy = byEpisodes
                    ? " ORDER BY e.episode_count DESC, e.id DESC "
                    : " ORDER BY COALESCE(e.resource_updated_at, e.synced_at) DESC, e.id DESC ";
            args.add(Math.max(offset == null ? 0 : offset, 0));
            args.add(limit);
            return jdbc.query("""
                    SELECT %s FROM external_catalog_items e
                    %s
                    %s
                    OFFSET ? LIMIT ?
                    """.formatted(LIST_COLUMNS, where, orderBy), mapper, args.toArray());
        }
        where.append(" AND (COALESCE(e.resource_updated_at, e.synced_at), e.id) < (?, ?) ");
        args.add(JdbcTime.t(beforeUpdated));
        args.add(beforeId);
        args.add(limit);
        return jdbc.query("""
                SELECT %s FROM external_catalog_items e
                %s
                ORDER BY COALESCE(e.resource_updated_at, e.synced_at) DESC, e.id DESC
                LIMIT ?
                """.formatted(LIST_COLUMNS, where), mapper, args.toArray());
    }

    public long count(UUID sourceId, Integer categoryId, String status, String genre, Boolean season) {
        return count(sourceId, categoryId, null, status, genre, season, null);
    }

    public long count(UUID sourceId, Integer categoryId, String q,
                      String status, String genre, Boolean season, String year) {
        StringBuilder where = new StringBuilder(" WHERE TRUE ");
        List<Object> args = new java.util.ArrayList<>();
        appendFilters(where, args, sourceId, categoryId, q, status, genre, season, year);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM external_catalog_items e %s".formatted(where), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    public record GenreStat(String tag, long items) {}

    /** 聚合全库番剧高频题材（合并目录原生 vod_class 与 Bangumi 题材，经 TagNormalizer 清洗与同义词折叠）。 */
    public List<GenreStat> topGenres(int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), 40);
        List<GenreStat> raw = jdbc.query("""
                WITH combined AS (
                    SELECT e.id AS item_id, t AS tag
                      FROM external_catalog_items e, unnest(e.tags) t
                    UNION
                    SELECT e.id AS item_id, t AS tag
                      FROM external_catalog_items e
                      JOIN bangumi_subjects b ON b.subject_id = e.bangumi_subject_id,
                           unnest(b.tags) t
                )
                SELECT tag, count(DISTINCT item_id) AS items
                  FROM combined
                 WHERE tag <> ''
                   AND length(tag) BETWEEN 2 AND 12
                   AND tag !~ '^[0-9]'
                 GROUP BY tag
                 ORDER BY count(DISTINCT item_id) DESC, tag ASC
                 LIMIT 120
                """, (rs, i) -> new GenreStat(rs.getString("tag"), rs.getLong("items")));
        var merged = new java.util.LinkedHashMap<String, Long>();
        for (GenreStat g : raw) {
            String canonical = TagNormalizer.canonicalize(g.tag());
            if (canonical != null) {
                merged.merge(canonical, g.items(), Long::sum);
            }
        }
        List<GenreStat> filtered = merged.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted(java.util.Comparator.<java.util.Map.Entry<String, Long>>comparingLong(java.util.Map.Entry::getValue)
                        .reversed().thenComparing(java.util.Map.Entry::getKey))
                .limit(effectiveLimit)
                .map(e -> new GenreStat(e.getKey(), e.getValue()))
                .toList();
        if (!filtered.isEmpty()) {
            return filtered;
        }
        return merged.entrySet().stream()
                .sorted(java.util.Comparator.<java.util.Map.Entry<String, Long>>comparingLong(java.util.Map.Entry::getValue)
                        .reversed().thenComparing(java.util.Map.Entry::getKey))
                .limit(effectiveLimit)
                .map(e -> new GenreStat(e.getKey(), e.getValue()))
                .toList();
    }

    private static void appendFilters(StringBuilder where, List<Object> args,
                                      UUID sourceId, Integer categoryId, String q,
                                      String status, String genre, Boolean season, String year) {
        if (sourceId != null) {
            where.append(" AND e.source_id = ? ");
            args.add(sourceId);
        }
        if (genre != null && !genre.isBlank()) {
            String[] aliases = TagNormalizer.aliasesFor(genre);
            where.append(" AND (e.tags && ?::text[] OR e.bangumi_subject_id IN (SELECT subject_id FROM bangumi_subjects WHERE tags && ?::text[])) ");
            args.add(aliases);
            args.add(aliases);
        }
        if (categoryId != null) {
            where.append(" AND e.category_id = ? ");
            args.add(categoryId);
        }
        if (Boolean.TRUE.equals(season)) {
            where.append(" AND e.bangumi_subject_id IN (SELECT subject_id FROM bangumi_subjects WHERE in_calendar) ");
        }
        if (year != null && !year.isBlank()) {
            if ("earlier".equalsIgnoreCase(year.trim())) {
                where.append(" AND (e.release_year < 2023 OR e.bangumi_subject_id IN (SELECT subject_id FROM bangumi_subjects WHERE air_date IS NOT NULL AND air_date < ?)) ");
                args.add(java.sql.Date.valueOf(java.time.LocalDate.of(2023, 1, 1)));
            } else if (year.trim().matches("^[0-9]{4}$")) {
                int y = Integer.parseInt(year.trim());
                where.append(" AND (e.release_year = ? OR e.bangumi_subject_id IN (SELECT subject_id FROM bangumi_subjects WHERE air_date >= ? AND air_date < ?)) ");
                args.add(y);
                args.add(java.sql.Date.valueOf(java.time.LocalDate.of(y, 1, 1)));
                args.add(java.sql.Date.valueOf(java.time.LocalDate.of(y + 1, 1, 1)));
            }
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND e.name ILIKE ? ESCAPE '\\' ");
            args.add("%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (status != null && !status.isBlank()) {
            switch (status) {
                case "completed" -> where.append(" AND e.remarks LIKE '%完结%' AND e.remarks NOT LIKE '更新至%' ");
                case "ongoing" -> where.append(" AND e.remarks LIKE '更新至%' ");
                case "movie" -> where.append(
                        " AND (e.remarks ILIKE '%HD%' OR e.remarks ILIKE '%剧场版%' OR e.category_name ILIKE '%剧场%') ");
                default -> throw new IllegalArgumentException("未知状态筛选：" + status);
            }
        }
    }

    public Optional<UUID> findIdByVod(UUID sourceId, long vodId) {
        return jdbc.query("SELECT id FROM external_catalog_items WHERE source_id = ? AND vod_id = ?",
                (rs, i) -> rs.getObject("id", UUID.class), sourceId, vodId).stream().findFirst();
    }

    public void linkSubject(UUID itemId, long subjectId) {
        jdbc.update("UPDATE external_catalog_items SET bangumi_subject_id = ? WHERE id = ?",
                subjectId, itemId);
    }

    public Optional<ExternalCatalogItem> findItem(UUID id) {
        return jdbc.query("""
                SELECT id, source_id, vod_id, name, cover_url, remarks, category_id, category_name,
                       play_from, play_groups, episode_count, work_id, bangumi_subject_id,
                       resource_updated_at, synced_at
                  FROM external_catalog_items
                 WHERE id = ?
                """, mapper, id).stream().findFirst();
    }

    /** 读取条目原生题材标签（规避 DataClassRowMapper 对 PgArray 的限制）。 */
    public List<String> findTags(UUID id) {
        return jdbc.query("SELECT tags FROM external_catalog_items WHERE id = ?", (rs, i) -> {
            var arr = rs.getArray("tags");
            return arr == null ? List.<String>of() : List.of((String[]) arr.getArray());
        }, id).stream().findFirst().orElse(List.of());
    }

    public void markMaterialized(UUID itemId, UUID workId) {
        jdbc.update("UPDATE external_catalog_items SET work_id = ? WHERE id = ?", workId, itemId);
    }

    public Optional<com.mangashelf.ingestion.Job> findLatestBangumiSyncJob() {
        return jdbc.query(
                "SELECT * FROM jobs WHERE kind = ? ORDER BY created_at DESC LIMIT 1",
                new DataClassRowMapper<>(com.mangashelf.ingestion.Job.class),
                com.mangashelf.ingestion.Job.KIND_BANGUMI_SYNC).stream().findFirst();
    }

    /** 最近一次成功的目录同步所用资源源（反查匹配优先用真实在用的源）。 */
    public Optional<UUID> findLatestCatalogSyncSourceId() {
        return jdbc.queryForList(
                "SELECT sync_source_id FROM jobs WHERE kind = ? AND status = 'SUCCEEDED' "
                + "AND sync_source_id IS NOT NULL ORDER BY created_at DESC LIMIT 1",
                UUID.class, com.mangashelf.ingestion.Job.KIND_CATALOG_SYNC).stream().findFirst();
    }

    public Optional<com.mangashelf.ingestion.Job> findLatestSyncJob(UUID sourceId) {
        return jdbc.query("""
                SELECT * FROM jobs WHERE kind = ? AND sync_source_id = ?
                ORDER BY created_at DESC LIMIT 1
                """, new DataClassRowMapper<>(com.mangashelf.ingestion.Job.class),
                com.mangashelf.ingestion.Job.KIND_CATALOG_SYNC, sourceId).stream().findFirst();
    }

    public Optional<com.mangashelf.ingestion.Job> findLatestSucceededSyncJob(UUID sourceId) {
        return jdbc.query("""
                SELECT * FROM jobs WHERE kind = ? AND sync_source_id = ? AND status = 'SUCCEEDED'
                ORDER BY created_at DESC LIMIT 1
                """, new DataClassRowMapper<>(com.mangashelf.ingestion.Job.class),
                com.mangashelf.ingestion.Job.KIND_CATALOG_SYNC, sourceId).stream().findFirst();
    }

    public Optional<Instant> findMaxResourceUpdatedAt(UUID sourceId) {
        java.time.OffsetDateTime ts = jdbc.queryForObject(
                "SELECT max(resource_updated_at) FROM external_catalog_items WHERE source_id = ?",
                java.time.OffsetDateTime.class, sourceId);
        return ts == null ? Optional.empty() : Optional.of(ts.toInstant());
    }
}
