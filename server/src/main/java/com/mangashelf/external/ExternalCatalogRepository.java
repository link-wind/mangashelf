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
    public List<ExternalCatalogItem> page(UUID sourceId, Integer categoryId, String q,
                                          Instant beforeUpdated, UUID beforeId, int limit) {
        StringBuilder where = new StringBuilder(" WHERE TRUE ");
        List<Object> args = new java.util.ArrayList<>();
        if (sourceId != null) {
            where.append(" AND source_id = ? ");
            args.add(sourceId);
        }
        if (categoryId != null) {
            where.append(" AND category_id = ? ");
            args.add(categoryId);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND name ILIKE ? ESCAPE '\\' ");
            args.add("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
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

    public long count(UUID sourceId, Integer categoryId) {
        StringBuilder where = new StringBuilder(" WHERE TRUE ");
        List<Object> args = new java.util.ArrayList<>();
        if (sourceId != null) {
            where.append(" AND source_id = ? ");
            args.add(sourceId);
        }
        if (categoryId != null) {
            where.append(" AND category_id = ? ");
            args.add(categoryId);
        }
        return jdbc.queryForObject(
                "SELECT count(*) FROM external_catalog_items %s".formatted(where), Long.class, args.toArray());
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

    public Optional<com.mangashelf.ingestion.Job> findLatestSyncJob(UUID sourceId) {
        return jdbc.query("""
                SELECT * FROM jobs WHERE kind = ? AND sync_source_id = ?
                ORDER BY created_at DESC LIMIT 1
                """, new DataClassRowMapper<>(com.mangashelf.ingestion.Job.class),
                com.mangashelf.ingestion.Job.KIND_CATALOG_SYNC, sourceId).stream().findFirst();
    }
}
