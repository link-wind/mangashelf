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

    public void requireChapterExists(UUID chapterId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chapters WHERE id = ?",
                Integer.class, chapterId);
        if (n == null || n == 0) {
            throw new ApiException(404, "CHAPTER_NOT_FOUND", "章节不存在。");
        }
    }
}
