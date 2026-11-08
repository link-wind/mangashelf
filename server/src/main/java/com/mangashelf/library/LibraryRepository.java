package com.mangashelf.library;

import com.mangashelf.config.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 书架与阅读进度（DESIGN 8.2 / 14.3）；显式 SQL，冲突语义见条件更新。 */
@Repository
public class LibraryRepository {

    private final JdbcTemplate jdbc;

    public LibraryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── 收藏 ──

    public boolean addEntry(UUID userId, UUID workId) {
        return jdbc.update("""
                INSERT INTO library_entries (user_id, work_id) VALUES (?, ?)
                ON CONFLICT (user_id, work_id) DO NOTHING
                """, userId, workId) > 0;
    }

    public int removeEntry(UUID userId, UUID workId) {
        return jdbc.update("DELETE FROM library_entries WHERE user_id = ? AND work_id = ?",
                userId, workId);
    }

    public record ShelfEntry(UUID workId, String title, String workStatus, String visibility,
                             Instant entryCreatedAt, UUID lastChapterId, Integer lastPageOrdinal,
                             Instant lastReadAt) {
    }

    public List<ShelfEntry> listShelf(UUID userId) {
        String sql = """
                SELECT w.id AS work_id, w.title, w.status AS work_status, w.visibility,
                       l.created_at AS entry_created_at,
                       p.chapter_id AS last_chapter_id, p.page_ordinal AS last_page_ordinal,
                       p.updated_at AS last_read_at
                FROM library_entries l
                JOIN works w ON w.id = l.work_id
                LEFT JOIN reading_progress p ON p.user_id = l.user_id
                    AND p.chapter_id IN (SELECT id FROM chapters WHERE work_id = w.id)
                WHERE l.user_id = ?
                ORDER BY l.created_at DESC
                """;
        return jdbc.query(sql, (rs, i) -> new ShelfEntry(
                rs.getObject("work_id", UUID.class),
                rs.getString("title"),
                rs.getString("work_status"),
                rs.getString("visibility"),
                rs.getObject("entry_created_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getObject("last_chapter_id", UUID.class),
                rs.getObject("last_page_ordinal") == null ? null : rs.getInt("last_page_ordinal"),
                rs.getObject("last_read_at") == null ? null
                        : rs.getObject("last_read_at", java.time.OffsetDateTime.class).toInstant()
        ), userId);
    }

    // ── 阅读进度（DESIGN 14.3）──

    /** 条件更新：版本匹配才覆盖并递增；返回新版本号，空即冲突。 */
    public Optional<Integer> upsertProgress(UUID userId, UUID chapterId, UUID revisionId,
                                            int pageOrdinal, int expectedVersion) {
        List<Integer> versions = jdbc.query("""
                INSERT INTO reading_progress (user_id, chapter_id, chapter_revision_id, page_ordinal,
                                              version, updated_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (user_id, chapter_id) DO UPDATE
                SET chapter_revision_id = EXCLUDED.chapter_revision_id,
                    page_ordinal = EXCLUDED.page_ordinal,
                    version = reading_progress.version + 1,
                    updated_at = now()
                WHERE reading_progress.version = ?
                RETURNING version
                """, (rs, i) -> rs.getInt("version"),
                userId, chapterId, revisionId, pageOrdinal, expectedVersion + 1, expectedVersion);
        return versions.stream().findFirst();
    }

    public record ProgressRow(UUID chapterId, UUID revisionId, int pageOrdinal, int version) {
    }

    public Optional<ProgressRow> findProgress(UUID userId, UUID chapterId) {
        return jdbc.query("""
                SELECT chapter_id, chapter_revision_id, page_ordinal, version
                FROM reading_progress WHERE user_id = ? AND chapter_id = ?
                """, (rs, i) -> new ProgressRow(rs.getObject("chapter_id", UUID.class),
                rs.getObject("chapter_revision_id", UUID.class), rs.getInt("page_ordinal"),
                rs.getInt("version")), userId, chapterId).stream().findFirst();
    }

    public record ContinueRow(UUID chapterId, UUID revisionId, int pageOrdinal, UUID workId,
                              String workTitle) {
    }

    /** 最近阅读（我的书架“继续阅读”，DESIGN 4）：按更新时间倒序。 */
    public List<ContinueRow> listRecentProgress(UUID userId, int limit) {
        String sql = """
                SELECT p.chapter_id, p.chapter_revision_id, p.page_ordinal, w.id AS work_id, w.title
                FROM reading_progress p
                JOIN chapters c ON c.id = p.chapter_id
                JOIN works w ON w.id = c.work_id
                WHERE p.user_id = ?
                ORDER BY p.updated_at DESC
                LIMIT ?
                """;
        return jdbc.query(sql, (rs, i) -> new ContinueRow(
                rs.getObject("chapter_id", UUID.class),
                rs.getObject("chapter_revision_id", UUID.class),
                rs.getInt("page_ordinal"),
                rs.getObject("work_id", UUID.class),
                rs.getString("title")), userId, limit);
    }

    public void requireChapterExists(UUID chapterId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chapters WHERE id = ?",
                Integer.class, chapterId);
        if (n == null || n == 0) {
            throw new ApiException(404, "CHAPTER_NOT_FOUND", "章节不存在。");
        }
    }
}
