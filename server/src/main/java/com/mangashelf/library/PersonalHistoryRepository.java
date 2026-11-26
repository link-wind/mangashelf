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
 * 个人活动（separate-favorites-and-history）：历史按章节/集展开，
 * 继续观看按作品或目录条目聚合；数据来自现有进度表，不新增事件表。
 */
@Repository
public class PersonalHistoryRepository {

    private final JdbcTemplate jdbc;

    public PersonalHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** chapterId 对本地阅读是 UUID 字符串、对外部漫画是源站章节标识，统一按字符串传递。 */
    public record ActivityRow(String type, String title, String subtitle, String coverUrl,
                              UUID workId, UUID catalogItemId, String chapterId,
                              UUID revisionId, Integer pageOrdinal, UUID animationId,
                              UUID episodeId, Integer ordinal, Long positionMs, Long durationMs,
                              String source, String mangaId, Instant updatedAt) {}

    public List<ActivityRow> history(UUID userId, int limit) {
        List<ActivityRow> rows = new ArrayList<>();
        rows.addAll(jdbc.query("""
                SELECT w.id AS work_id, w.title, c.title AS subtitle,
                       rp.chapter_id, rp.chapter_revision_id, rp.page_ordinal, rp.updated_at
                  FROM reading_progress rp
                  JOIN chapters c ON c.id = rp.chapter_id
                  JOIN works w ON w.id = c.work_id
                 WHERE rp.user_id = ?
                 ORDER BY rp.updated_at DESC LIMIT ?
                """, (rs, i) -> new ActivityRow("work-read", rs.getString("title"),
                rs.getString("subtitle"), null, rs.getObject("work_id", UUID.class), null,
                String.valueOf(rs.getObject("chapter_id", UUID.class)),
                rs.getObject("chapter_revision_id", UUID.class),
                rs.getInt("page_ordinal"), null, null, null, null, null, null, null,
                instant(rs.getObject("updated_at", OffsetDateTime.class))), userId, limit));

        rows.addAll(jdbc.query("""
                SELECT p.source, p.manga_id, p.chapter_id, p.chapter_label, p.page, p.title,
                       COALESCE(
                           NULLIF(p.cover_url, ''),
                           NULLIF(mc.cover_url, ''),
                           (SELECT NULLIF(mc2.cover_url, '')
                              FROM manga_catalog_items mc2
                             WHERE mc2.name = p.title AND mc2.cover_url <> ''
                             ORDER BY mc2.synced_at DESC LIMIT 1)
                       ) AS cover_url,
                       p.updated_at
                  FROM external_manga_progress p
                  LEFT JOIN manga_catalog_items mc
                    ON mc.source = p.source
                   AND mc.source_item_id = regexp_replace(p.manga_id, '^mc', '')
                 WHERE p.user_id = ?
                 ORDER BY p.updated_at DESC LIMIT ?
                """, (rs, i) -> new ActivityRow("manga-read", rs.getString("title"),
                rs.getString("chapter_label"), rs.getString("cover_url"), null, null,
                rs.getString("chapter_id"), null, rs.getInt("page"), null, null, null, null, null,
                rs.getString("source"), rs.getString("manga_id"),
                instant(rs.getObject("updated_at", OffsetDateTime.class))), userId, limit));

        rows.addAll(jdbc.query("""
                SELECT a.id AS animation_id, a.title, wp.position_ms, wp.updated_at
                  FROM watch_progress wp
                  JOIN animations a ON a.id = wp.animation_id
                 WHERE wp.user_id = ?
                 ORDER BY wp.updated_at DESC LIMIT ?
                """, (rs, i) -> new ActivityRow("animation-watch", rs.getString("title"), null,
                null, null, null, null, null, null, rs.getObject("animation_id", UUID.class),
                null, null, rs.getLong("position_ms"), null, null, null,
                instant(rs.getObject("updated_at", OffsetDateTime.class))), userId, limit));

        rows.addAll(workWatch(userId, limit, false));
        rows.addAll(catalogWatch(userId, limit, false));
        return sortAndLimit(rows, limit);
    }

    public List<ActivityRow> continueWatching(UUID userId, int limit) {
        List<ActivityRow> rows = new ArrayList<>();
        // 本地阅读按作品聚合：每部作品只保留最近章节。
        rows.addAll(jdbc.query("""
                SELECT DISTINCT ON (w.id) w.id AS work_id, w.title, c.title AS subtitle,
                       rp.chapter_id, rp.chapter_revision_id, rp.page_ordinal, rp.updated_at
                  FROM reading_progress rp
                  JOIN chapters c ON c.id = rp.chapter_id
                  JOIN works w ON w.id = c.work_id
                 WHERE rp.user_id = ?
                 ORDER BY w.id, rp.updated_at DESC
                """, (rs, i) -> new ActivityRow("work-read", rs.getString("title"),
                rs.getString("subtitle"), null, rs.getObject("work_id", UUID.class), null,
                String.valueOf(rs.getObject("chapter_id", UUID.class)),
                rs.getObject("chapter_revision_id", UUID.class),
                rs.getInt("page_ordinal"), null, null, null, null, null, null, null,
                instant(rs.getObject("updated_at", OffsetDateTime.class))), userId));

        rows.addAll(jdbc.query("""
                SELECT p.source, p.manga_id, p.chapter_id, p.chapter_label, p.page, p.title,
                       COALESCE(
                           NULLIF(p.cover_url, ''),
                           NULLIF(mc.cover_url, ''),
                           (SELECT NULLIF(mc2.cover_url, '')
                              FROM manga_catalog_items mc2
                             WHERE mc2.name = p.title AND mc2.cover_url <> ''
                             ORDER BY mc2.synced_at DESC LIMIT 1)
                       ) AS cover_url,
                       p.updated_at
                  FROM external_manga_progress p
                  LEFT JOIN manga_catalog_items mc
                    ON mc.source = p.source
                   AND mc.source_item_id = regexp_replace(p.manga_id, '^mc', '')
                 WHERE p.user_id = ?
                """, (rs, i) -> new ActivityRow("manga-read", rs.getString("title"),
                rs.getString("chapter_label"), rs.getString("cover_url"), null, null,
                rs.getString("chapter_id"), null, rs.getInt("page"), null, null, null, null, null,
                rs.getString("source"), rs.getString("manga_id"),
                instant(rs.getObject("updated_at", OffsetDateTime.class))), userId));

        rows.addAll(jdbc.query("""
                SELECT a.id AS animation_id, a.title, wp.position_ms, wp.updated_at
                  FROM watch_progress wp
                  JOIN animations a ON a.id = wp.animation_id
                 WHERE wp.user_id = ?
                """, (rs, i) -> new ActivityRow("animation-watch", rs.getString("title"), null,
                null, null, null, null, null, null, rs.getObject("animation_id", UUID.class),
                null, null, rs.getLong("position_ms"), null, null, null,
                instant(rs.getObject("updated_at", OffsetDateTime.class))), userId));

        rows.addAll(workWatch(userId, limit, true));
        rows.addAll(catalogWatch(userId, limit, true));
        return sortAndLimit(rows, limit);
    }

    /** 作品态外部观看；grouped 时每个作品只保留最近一条。 */
    private List<ActivityRow> workWatch(UUID userId, int limit, boolean grouped) {
        String prefix = grouped
                ? "SELECT DISTINCT ON (p.work_id) p.work_id, p.ordinal"
                : "SELECT p.work_id, p.ordinal";
        String order = grouped
                ? "ORDER BY p.work_id, p.updated_at DESC"
                : "ORDER BY p.updated_at DESC LIMIT ?";
        String sql = prefix + """
                , p.episode_id, p.position_ms, p.duration_ms, p.updated_at,
                       w.title, e.title AS subtitle,
                       (SELECT CASE
                                 WHEN c.bangumi_subject_id IS NOT NULL THEN '/api/v1/bangumi-covers/' || c.bangumi_subject_id || '?size=large'
                                 ELSE '/api/v1/catalog-covers/' || c.id
                               END
                          FROM external_catalog_items c
                         WHERE c.work_id = p.work_id AND (c.bangumi_subject_id IS NOT NULL OR c.cover_url <> '') LIMIT 1) AS cover_url
                  FROM external_watch_progress p
                  JOIN works w ON w.id = p.work_id
                  LEFT JOIN external_episodes e ON e.id = p.episode_id
                 WHERE p.user_id = ?
                """ + order;
        Object[] args = grouped ? new Object[]{userId} : new Object[]{userId, limit};
        return jdbc.query(sql, (rs, i) -> new ActivityRow("external-watch", rs.getString("title"),
                rs.getString("subtitle"), rs.getString("cover_url"),
                rs.getObject("work_id", UUID.class), null, null, null, null, null,
                rs.getObject("episode_id", UUID.class), rs.getInt("ordinal"),
                rs.getLong("position_ms"), rs.getObject("duration_ms", Long.class), null, null,
                instant(rs.getObject("updated_at", OffsetDateTime.class))), args);
    }

    /** 浏览态外部观看；grouped 时每个目录条目只保留最近一条。 */
    private List<ActivityRow> catalogWatch(UUID userId, int limit, boolean grouped) {
        String prefix = grouped
                ? "SELECT DISTINCT ON (p.catalog_item_id) p.catalog_item_id, p.ordinal"
                : "SELECT p.catalog_item_id, p.ordinal";
        String order = grouped
                ? "ORDER BY p.catalog_item_id, p.updated_at DESC"
                : "ORDER BY p.updated_at DESC LIMIT ?";
        String sql = prefix + """
                , p.episode_id, p.position_ms, p.duration_ms, p.updated_at,
                       c.name AS title, e.title AS subtitle,
                       CASE
                         WHEN c.bangumi_subject_id IS NOT NULL THEN '/api/v1/bangumi-covers/' || c.bangumi_subject_id || '?size=large'
                         WHEN c.cover_url <> '' THEN '/api/v1/catalog-covers/' || c.id
                         ELSE ''
                       END AS cover_url
                  FROM catalog_watch_progress p
                  JOIN external_catalog_items c ON c.id = p.catalog_item_id
                  LEFT JOIN external_episodes e ON e.id = p.episode_id
                 WHERE p.user_id = ?
                """ + order;
        Object[] args = grouped ? new Object[]{userId} : new Object[]{userId, limit};
        return jdbc.query(sql, (rs, i) -> new ActivityRow("external-watch", rs.getString("title"),
                rs.getString("subtitle"), rs.getString("cover_url"), null,
                rs.getObject("catalog_item_id", UUID.class), null, null, null, null,
                rs.getObject("episode_id", UUID.class), rs.getInt("ordinal"),
                rs.getLong("position_ms"), rs.getObject("duration_ms", Long.class), null, null,
                instant(rs.getObject("updated_at", OffsetDateTime.class))), args);
    }

    private static List<ActivityRow> sortAndLimit(List<ActivityRow> rows, int limit) {
        rows.sort(Comparator.comparing(ActivityRow::updatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return rows.size() > limit ? rows.subList(0, limit) : rows;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
