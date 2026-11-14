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

    /** 单条 upsert（playGroups 为线路组原文 JSON 数组字符串）。 */
    public void upsert(UUID sourceId, long vodId, String name, String coverUrl, String remarks,
                       int categoryId, String categoryName, String playFrom, String playGroups,
                       int episodeCount, Instant resourceUpdatedAt) {
        jdbc.update("""
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
                """,
                UUID.randomUUID(), sourceId, vodId, name, coverUrl, remarks, categoryId,
                categoryName, playFrom, playGroups, episodeCount, JdbcTime.t(resourceUpdatedAt));
    }

    /** 目录浏览：resource_updated_at 降序（最近更新在前），(updated,id) 游标。 */
    /**
     * 目录分页（mobile-ui-index-and-reader）：status 依据 remarks/category_name 过滤，
     * sort=episodes 用集数排序 + 偏移分页（键集游标按更新时间，不适用集数序）。
     */
    public List<ExternalCatalogItem> page(UUID sourceId, Integer categoryId, String q,
                                          String status, String sort, String genre,
                                          Instant beforeUpdated, UUID beforeId,
                                          int offset, int limit) {
        StringBuilder where = new StringBuilder(" WHERE TRUE ");
        List<Object> args = new java.util.ArrayList<>();
        if (sourceId != null) {
            where.append(" AND source_id = ? ");
            args.add(sourceId);
        }
        if (genre != null && !genre.isBlank()) {
            // 经条目关联的 Bangumi 标签过滤（mobile-nav-genre-remove-mangadex）
            where.append(" AND bangumi_subject_id IN (SELECT subject_id FROM bangumi_subjects WHERE EXISTS (SELECT 1 FROM unnest(tags) t WHERE t = ?)) ");
            args.add(genre);
        }
        if (categoryId != null) {
            where.append(" AND category_id = ? ");
            args.add(categoryId);
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
                    "SELECT * FROM external_catalog_items %s".formatted(where), mapper, args.toArray());
        }
        where.append(" AND (COALESCE(resource_updated_at, synced_at), id) < (?, ?) ");
        args.add(JdbcTime.t(beforeUpdated));
        args.add(beforeId);
        args.add(limit);
        return jdbc.query("""
                SELECT * FROM external_catalog_items
                %s
                ORDER BY COALESCE(resource_updated_at, synced_at) DESC, id DESC
                LIMIT ?
                """.formatted(where), mapper, args.toArray());
    }

    public long count(UUID sourceId, Integer categoryId, String status, String genre) {
        StringBuilder where = new StringBuilder(" WHERE TRUE ");
        List<Object> args = new java.util.ArrayList<>();
        if (sourceId != null) {
            where.append(" AND source_id = ? ");
            args.add(sourceId);
        }
        if (genre != null && !genre.isBlank()) {
            where.append(" AND bangumi_subject_id IN (SELECT subject_id FROM bangumi_subjects WHERE EXISTS (SELECT 1 FROM unnest(tags) t WHERE t = ?)) ");
            args.add(genre);
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
}
