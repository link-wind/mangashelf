package com.mangashelf.library;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 统一收藏（separate-favorites-and-history）：单表三类明确目标，
 * 由数据库 CHECK 与部分唯一索引保证"恰好一个目标 + 同目标唯一"。
 */
@Repository
public class FavoriteRepository {

    private static final int MAX_ITEMS = 200;

    private final JdbcTemplate jdbc;

    public FavoriteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record FavoriteRow(String type, String title, String coverUrl, UUID workId,
                              String source, String mangaId, UUID catalogItemId, Instant createdAt) {}

    public void addWork(UUID userId, UUID workId) {
        jdbc.update("""
                INSERT INTO favorites (id, user_id, work_id) VALUES (?, ?, ?)
                ON CONFLICT (user_id, work_id) WHERE work_id IS NOT NULL DO NOTHING
                """, UUID.randomUUID(), userId, workId);
    }

    public void removeWork(UUID userId, UUID workId) {
        jdbc.update("DELETE FROM favorites WHERE user_id = ? AND work_id = ?", userId, workId);
    }

    public void addManga(UUID userId, String source, String mangaId, String title) {
        jdbc.update("""
                INSERT INTO favorites (id, user_id, manga_source, manga_id, manga_title)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id, manga_source, manga_id) WHERE manga_id IS NOT NULL DO NOTHING
                """, UUID.randomUUID(), userId, source, mangaId, title);
    }

    public void removeManga(UUID userId, String source, String mangaId) {
        jdbc.update("DELETE FROM favorites WHERE user_id = ? AND manga_source = ? AND manga_id = ?",
                userId, source, mangaId);
    }

    public void addCatalog(UUID userId, UUID itemId) {
        jdbc.update("""
                INSERT INTO favorites (id, user_id, catalog_item_id) VALUES (?, ?, ?)
                ON CONFLICT (user_id, catalog_item_id) WHERE catalog_item_id IS NOT NULL DO NOTHING
                """, UUID.randomUUID(), userId, itemId);
    }

    public void removeCatalog(UUID userId, UUID itemId) {
        jdbc.update("DELETE FROM favorites WHERE user_id = ? AND catalog_item_id = ?",
                userId, itemId);
    }

    public boolean catalogItemExists(UUID itemId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM external_catalog_items WHERE id = ?", Integer.class, itemId);
        return n != null && n > 0;
    }

    /** 统一列表：可按类型筛选；封面仅取已读漫画的进度快照，不接受任意 URL。 */
    public List<FavoriteRow> list(UUID userId, String type) {
        List<FavoriteRow> rows = new ArrayList<>();
        if (type == null || type.isBlank() || "all".equals(type) || "work".equals(type)) {
            rows.addAll(jdbc.query("""
                    SELECT 'work' AS type, w.title, NULL::text AS cover_url, f.work_id,
                           NULL::text AS manga_source, NULL::text AS manga_id,
                           NULL::uuid AS catalog_item_id, f.created_at
                      FROM favorites f
                      JOIN works w ON w.id = f.work_id
                     WHERE f.user_id = ? AND f.work_id IS NOT NULL
                    """, (rs, i) -> new FavoriteRow(rs.getString("type"), rs.getString("title"),
                    rs.getString("cover_url"), rs.getObject("work_id", UUID.class),
                    null, null, null, toInstant(rs.getObject("created_at", OffsetDateTime.class))),
                    userId));
        }
        if (type == null || type.isBlank() || "all".equals(type) || "manga".equals(type)) {
            rows.addAll(jdbc.query("""
                    SELECT 'manga' AS type,
                           COALESCE(NULLIF(f.manga_title, ''), NULLIF(p.title, ''), NULLIF(mc.name, ''), f.manga_id) AS title,
                           COALESCE(NULLIF(p.cover_url, ''), NULLIF(mc.cover_url, '')) AS cover_url,
                           NULL::uuid AS work_id,
                           f.manga_source, f.manga_id, NULL::uuid AS catalog_item_id, f.created_at
                      FROM favorites f
                      LEFT JOIN external_manga_progress p
                             ON p.user_id = f.user_id
                            AND p.source = f.manga_source
                            AND p.manga_id = f.manga_id
                      LEFT JOIN manga_catalog_items mc
                             ON mc.source = f.manga_source
                            AND mc.source_item_id = f.manga_id
                     WHERE f.user_id = ? AND f.manga_id IS NOT NULL
                    """, (rs, i) -> new FavoriteRow(rs.getString("type"), rs.getString("title"),
                    rs.getString("cover_url"), null, rs.getString("manga_source"),
                    rs.getString("manga_id"), null,
                    toInstant(rs.getObject("created_at", OffsetDateTime.class))),
                    userId));
        }
        if (type == null || type.isBlank() || "all".equals(type) || "catalog".equals(type)) {
            rows.addAll(jdbc.query("""
                    SELECT 'catalog' AS type, c.name AS title,
                           CASE
                             WHEN c.bangumi_subject_id IS NOT NULL THEN '/api/v1/bangumi-covers/' || c.bangumi_subject_id || '?size=common'
                             WHEN c.cover_url <> '' THEN '/api/v1/catalog-covers/' || c.id
                             ELSE ''
                           END AS cover_url,
                           NULL::uuid AS work_id,
                           NULL::text AS manga_source, NULL::text AS manga_id,
                           f.catalog_item_id, f.created_at
                      FROM favorites f
                      JOIN external_catalog_items c ON c.id = f.catalog_item_id
                     WHERE f.user_id = ? AND f.catalog_item_id IS NOT NULL
                    """, (rs, i) -> new FavoriteRow(rs.getString("type"), rs.getString("title"),
                    rs.getString("cover_url"), null, null, null,
                    rs.getObject("catalog_item_id", UUID.class),
                    toInstant(rs.getObject("created_at", OffsetDateTime.class))),
                    userId));
        }
        rows.sort(Comparator.comparing(FavoriteRow::createdAt).reversed());
        return rows.size() > MAX_ITEMS ? rows.subList(0, MAX_ITEMS) : rows;
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
