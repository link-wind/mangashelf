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
        return jdbc.queryForObject("""
                INSERT INTO external_catalog_items
                    (id, source_id, vod_id, name, cover_url, remarks, category_id, category_name,
                     play_from, play_groups, episode_count, resource_updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (source_id, vod_id) DO UPDATE SET
                    name = EXCLUDED.name, cover_url = EXCLUDED.cover_url, remarks = EXCLUDED.remarks,
                    category_id = EXCLUDED.category_id, category_name = EXCLUDED.category_name,
                    play_from = EXCLUDED.play_from, play_groups = EXCLUDED.play_groups,
                    episode_count = EXCLUDED.episode_count,
                    resource_updated_at = EXCLUDED.resource_updated_at, synced_at = now()
                RETURNING id, work_id
                """,
                (rs, i) -> new UpsertedItem(
                        rs.getObject("id", UUID.class),
                        rs.getObject("work_id", UUID.class)),
                UUID.randomUUID(), sourceId, vodId, name, coverUrl, remarks, categoryId,
                categoryName, playFrom, playGroups, episodeCount, JdbcTime.t(resourceUpdatedAt));
    }

    /** 列表页精简投影：跳过平均 ~20KB/行的 play_groups 巨型 JSONB（详情接口 findItem 仍返回完整列）。 */
    private static final String LIST_COLUMNS = """
            id, source_id, vod_id, name, cover_url, remarks, category_id, category_name,
            play_from, '' AS play_groups, episode_count, work_id, bangumi_subject_id,
            resource_updated_at, synced_at
            """;

    /**
     * 目录分页（benchmark-db-and-hotspot-cache）：
     * - 精简列投影消除列表页巨型 JSONB 传输与序列化放大；
     * - genre 使用 tags @> ARRAY[?] 命中 bangumi_subjects GIN 索引；
     * - 排序键命中 V29 表达式复合索引与集数降序索引。
     */
    public List<ExternalCatalogItem> page(UUID sourceId, Integer categoryId, String q,
                                          String status, String sort, String genre, Boolean season,
                                          Instant beforeUpdated, UUID beforeId,
                                          int offset, int limit) {
        StringBuilder where = new StringBuilder(" WHERE TRUE ");
        List<Object> args = new java.util.ArrayList<>();
        if (sourceId != null) {
            where.append(" AND source_id = ? ");
            args.add(sourceId);
        }
        if (genre != null && !genre.isBlank()) {
            where.append(" AND bangumi_subject_id IN (SELECT subject_id FROM bangumi_subjects WHERE tags @> ARRAY[?]::text[]) ");
            args.add(genre);
        }
        if (categoryId != null) {
            where.append(" AND category_id = ? ");
            args.add(categoryId);
        }
        if (Boolean.TRUE.equals(season)) {
            where.append(" AND bangumi_subject_id IN (SELECT subject_id FROM bangumi_subjects WHERE in_calendar) ");
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND name ILIKE ? ESCAPE '\\' ");
            args.add("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (status != null && !status.isBlank()) {
            switch (status) {
                case "completed" -> where.append(" AND remarks LIKE '%完结%' AND remarks NOT LIKE '更新至%' ");
                case "ongoing" -> where.append(" AND remarks LIKE '更新至%' ");
                case "movie" -> where.append(
                        " AND (remarks ILIKE '%HD%' OR remarks ILIKE '%剧场版%' OR category_name ILIKE '%剧场%') ");
                default -> throw new IllegalArgumentException("未知状态筛选：" + status);
            }
        }
        boolean byEpisodes = "episodes".equals(sort);
        if (byEpisodes) {
            where.append(" ORDER BY episode_count DESC, id DESC OFFSET ? LIMIT ? ");
            args.add(offset);
            args.add(limit);
            return jdbc.query(
                    "SELECT %s FROM external_catalog_items %s".formatted(LIST_COLUMNS, where),
                    mapper, args.toArray());
        }
        where.append(" AND (COALESCE(resource_updated_at, synced_at), id) < (?, ?) ");
        args.add(JdbcTime.t(beforeUpdated));
        args.add(beforeId);
        args.add(limit);
        return jdbc.query("""
                SELECT %s FROM external_catalog_items
                %s
                ORDER BY COALESCE(resource_updated_at, synced_at) DESC, id DESC
                LIMIT ?
                """.formatted(LIST_COLUMNS, where), mapper, args.toArray());
    }

    public long count(UUID sourceId, Integer categoryId, String status, String genre, Boolean season) {
        StringBuilder where = new StringBuilder(" WHERE TRUE ");
        List<Object> args = new java.util.ArrayList<>();
        if (sourceId != null) {
            where.append(" AND source_id = ? ");
            args.add(sourceId);
        }
        if (genre != null && !genre.isBlank()) {
            where.append(" AND bangumi_subject_id IN (SELECT subject_id FROM bangumi_subjects WHERE tags @> ARRAY[?]::text[]) ");
            args.add(genre);
        }
        if (Boolean.TRUE.equals(season)) {
            where.append(" AND bangumi_subject_id IN (SELECT subject_id FROM bangumi_subjects WHERE in_calendar) ");
        }
        if (categoryId != null) {
            where.append(" AND category_id = ? ");
            args.add(categoryId);
        }
        if (status != null && !status.isBlank()) {
            switch (status) {
                case "completed" -> where.append(" AND remarks LIKE '%完结%' AND remarks NOT LIKE '更新至%' ");
                case "ongoing" -> where.append(" AND remarks LIKE '更新至%' ");
                case "movie" -> where.append(
                        " AND (remarks ILIKE '%HD%' OR remarks ILIKE '%剧场版%' OR category_name ILIKE '%剧场%') ");
                default -> throw new IllegalArgumentException("未知状态筛选：" + status);
            }
        }
        return jdbc.queryForObject(
                "SELECT count(*) FROM external_catalog_items %s".formatted(where), Long.class, args.toArray());
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
        return jdbc.query("SELECT * FROM external_catalog_items WHERE id = ?", mapper, id).stream().findFirst();
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
