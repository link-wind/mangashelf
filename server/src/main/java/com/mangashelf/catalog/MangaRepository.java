package com.mangashelf.catalog;

import com.mangashelf.config.JdbcTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 漫画专区（M15）：只列出「有已发布章节」的作品，封面取首章首页的缩略图（无缩略图回退阅读图），
 * 因此不需要额外的封面上传流程。
 */
@Repository
public class MangaRepository {

    private final JdbcTemplate jdbc;

    public MangaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record MangaRow(UUID id, String title, String description, String visibility,
                           String status, int chapterCount, Integer latestOrdinal,
                           String latestTitle, String coverKey, Instant updatedAt) {}

    /** 可见范围与 works 一致：本人 或 PUBLIC+PUBLISHED；仅含有已发布章节的作品。 */
    public List<MangaRow> listVisible(UUID viewerId, String q, Instant beforeUpdated,
                                      UUID beforeId, int limit) {
        String filter = (q == null || q.isBlank()) ? "" : "AND w.title ILIKE ? ESCAPE '\\' ";
        Object[] args = (q == null || q.isBlank())
                ? new Object[]{viewerId, JdbcTime.t(beforeUpdated), beforeId, limit}
                : new Object[]{viewerId, like(q), JdbcTime.t(beforeUpdated), beforeId, limit};
        return jdbc.query("""
                SELECT w.id, w.title, w.description, w.visibility, w.status, w.updated_at,
                       COALESCE(ch.cnt, 0) AS chapter_count, ch.last_ordinal, ch.last_title,
                       cov.cover_key
                FROM works w
                LEFT JOIN LATERAL (
                    SELECT count(*) AS cnt, max(c.ordinal) AS last_ordinal,
                           (array_agg(c.title ORDER BY c.ordinal DESC))[1] AS last_title
                    FROM chapters c
                    WHERE c.work_id = w.id AND c.current_revision_id IS NOT NULL
                ) ch ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COALESCE(a2.object_key, a1.object_key) AS cover_key
                    FROM chapters c
                    JOIN chapter_revisions r ON r.id = c.current_revision_id
                    JOIN pages p ON p.chapter_revision_id = r.id
                    LEFT JOIN assets a1 ON a1.id = p.image_asset_id
                    LEFT JOIN assets a2 ON a2.id = p.thumbnail_asset_id
                    WHERE c.work_id = w.id AND r.status = 'PUBLISHED'
                    ORDER BY c.ordinal ASC, p.ordinal ASC
                    LIMIT 1
                ) cov ON TRUE
                WHERE (w.owner_id = ? OR (w.visibility = 'PUBLIC' AND w.status = 'PUBLISHED'))
                  AND COALESCE(ch.cnt, 0) > 0
                %s
                AND (w.updated_at, w.id) < (?, ?)
                ORDER BY w.updated_at DESC, w.id DESC
                LIMIT ?
                """.formatted(filter), (rs, i) -> new MangaRow(
                        rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getString("description"), rs.getString("visibility"),
                        rs.getString("status"), rs.getInt("chapter_count"),
                        (Integer) rs.getObject("last_ordinal"), rs.getString("last_title"),
                        rs.getString("cover_key"),
                        rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()),
                args);
    }

    /** 作品封面（首章首页缩略图）。 */
    public Optional<String> coverKey(UUID workId) {
        return jdbc.query("""
                SELECT COALESCE(a2.object_key, a1.object_key) AS cover_key
                FROM chapters c
                JOIN chapter_revisions r ON r.id = c.current_revision_id
                JOIN pages p ON p.chapter_revision_id = r.id
                LEFT JOIN assets a1 ON a1.id = p.image_asset_id
                LEFT JOIN assets a2 ON a2.id = p.thumbnail_asset_id
                WHERE c.work_id = ? AND r.status = 'PUBLISHED'
                ORDER BY c.ordinal ASC, p.ordinal ASC
                LIMIT 1
                """, (rs, i) -> rs.getString("cover_key"), workId).stream().findFirst();
    }

    public record ContinueTarget(UUID chapterId, UUID revisionId, int pageOrdinal) {}

    /** 续读目标：最近阅读进度；无进度则首章首页。 */
    public Optional<ContinueTarget> continueTarget(UUID userId, UUID workId) {
        List<ContinueTarget> progress = jdbc.query("""
                SELECT p.chapter_id, p.chapter_revision_id, p.page_ordinal
                FROM reading_progress p
                JOIN chapters c ON c.id = p.chapter_id
                WHERE p.user_id = ? AND c.work_id = ?
                ORDER BY p.updated_at DESC
                LIMIT 1
                """, (rs, i) -> new ContinueTarget(
                        rs.getObject("chapter_id", UUID.class),
                        rs.getObject("chapter_revision_id", UUID.class),
                        rs.getInt("page_ordinal")), userId, workId);
        if (!progress.isEmpty()) return Optional.of(progress.get(0));
        return jdbc.query("""
                SELECT c.id AS chapter_id, c.current_revision_id
                FROM chapters c
                JOIN chapter_revisions r ON r.id = c.current_revision_id
                WHERE c.work_id = ? AND r.status = 'PUBLISHED'
                ORDER BY c.ordinal ASC
                LIMIT 1
                """, (rs, i) -> new ContinueTarget(
                        rs.getObject("chapter_id", UUID.class),
                        rs.getObject("current_revision_id", UUID.class), 1), workId)
                .stream().findFirst();
    }

    /** 章节已读页数（用于详情页章节行标注）。 */
    public java.util.Map<UUID, Integer> progressByChapter(UUID userId, UUID workId) {
        java.util.Map<UUID, Integer> out = new java.util.HashMap<>();
        jdbc.query("""
                SELECT p.chapter_id, p.page_ordinal
                FROM reading_progress p
                JOIN chapters c ON c.id = p.chapter_id
                WHERE p.user_id = ? AND c.work_id = ?
                """, rs -> {
            out.put(rs.getObject("chapter_id", UUID.class), rs.getInt("page_ordinal"));
        }, userId, workId);
        return out;
    }

    private static String like(String s) {
        return "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
