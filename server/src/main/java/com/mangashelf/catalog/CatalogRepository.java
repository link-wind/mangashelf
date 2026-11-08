package com.mangashelf.catalog;

import com.mangashelf.config.JdbcTime;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 目录模块显式 SQL（DESIGN 6.2）。游标分页用 (created_at, id) 稳定键。 */
@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<Work> workMapper = new DataClassRowMapper<>(Work.class);
    private final RowMapper<Chapter> chapterMapper = new DataClassRowMapper<>(Chapter.class);
    private final RowMapper<ChapterRevision> revisionMapper = new DataClassRowMapper<>(ChapterRevision.class);
    private final RowMapper<Page> pageMapper = new DataClassRowMapper<>(Page.class);

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── works ──

    public UUID insertWork(UUID ownerId, String title, String description) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO works (id, owner_id, title, description) VALUES (?, ?, ?, ?)
                """, id, ownerId, title, description);
        return id;
    }

    public Optional<Work> findWork(UUID id) {
        return jdbc.query("SELECT * FROM works WHERE id = ?", workMapper, id).stream().findFirst();
    }

    /** 可见范围：owner 本人，或 PUBLIC 且 PUBLISHED（DESIGN 3.2/3.3，检索与详情共用同一判定）。 */
    public List<Work> listVisible(UUID viewerId, String titleFilter, Instant beforeCreatedAt,
                                  UUID beforeId, int limit) {
        String filter = titleFilter == null || titleFilter.isBlank()
                ? ""
                : "AND title ILIKE ? ESCAPE '\\'";
        // JDBC 参数用 OffsetDateTime（PG 驱动不推断 Instant 类型，见 JdbcTime）
        java.time.OffsetDateTime cursorTime = JdbcTime.t(beforeCreatedAt);
        Object[] args = titleFilter == null || titleFilter.isBlank()
                ? new Object[]{viewerId, cursorTime, beforeId, limit}
                : new Object[]{viewerId, likeEscape(titleFilter), cursorTime, beforeId, limit};
        return jdbc.query("""
                SELECT * FROM works
                WHERE (owner_id = ? OR (visibility = 'PUBLIC' AND status = 'PUBLISHED'))
                %s
                AND (created_at, id) < (?, ?)
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """.formatted(filter), workMapper, args);
    }

    private static String likeEscape(String s) {
        return "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** 作品卡片装饰信息（索引/首页）：动画与外部集计数 + 最新已发布动画海报的对象 Key。 */
    public record Decor(long animations, long episodes, String posterKey) {
    }

    public java.util.Map<UUID, Decor> decorations(java.util.Collection<UUID> workIds) {
        if (workIds.isEmpty()) return java.util.Map.of();
        String in = workIds.stream().map(x -> "?").reduce((a, b) -> a + "," + b).orElse("");
        String sql = """
                SELECT w.id,
                       (SELECT count(DISTINCT a2.id) FROM animations a2
                          JOIN animation_revisions ar ON ar.animation_id = a2.id AND ar.status = 'PUBLISHED'
                          JOIN animation_sources src ON src.animation_revision_id = ar.id
                          JOIN chapter_revisions cr ON cr.id = src.chapter_revision_id
                          JOIN chapters c ON c.id = cr.chapter_id
                         WHERE c.work_id = w.id) AS animations,
                       (SELECT count(*) FROM external_episodes e WHERE e.work_id = w.id) AS episodes,
                       (SELECT ast.object_key FROM animation_revisions ar
                          JOIN animations a2 ON a2.id = ar.animation_id
                          JOIN animation_sources src ON src.animation_revision_id = ar.id
                          JOIN chapter_revisions cr ON cr.id = src.chapter_revision_id
                          JOIN chapters c ON c.id = cr.chapter_id
                          JOIN assets ast ON ast.id = ar.poster_asset_id
                         WHERE c.work_id = w.id AND ar.status = 'PUBLISHED'
                         ORDER BY ar.revision_no DESC LIMIT 1) AS poster_key
                FROM works w WHERE w.id IN (%s)
                """.formatted(in);
        java.util.Map<UUID, Decor> out = new java.util.HashMap<>();
        jdbc.query(sql, rs -> {
            Decor d = new Decor(rs.getLong("animations"), rs.getLong("episodes"), rs.getString("poster_key"));
            out.put(rs.getObject("id", UUID.class), d);
        }, workIds.toArray());
        return out;
    }

    /** 标题召回（DESIGN 15.3 文本分支）：授权范围 + ILIKE/trgm，按相似度排序。 */
    public List<Work> searchWorksByTitle(UUID viewerId, String q, int limit) {
        return jdbc.query("""
                SELECT * FROM works w
                WHERE (w.owner_id = ? OR (w.visibility = 'PUBLIC' AND w.status = 'PUBLISHED'))
                  AND (w.title ILIKE '%' || ? || '%' OR w.title % ?)
                ORDER BY GREATEST(similarity(w.title, ?), CASE WHEN w.title ILIKE '%' || ? || '%' THEN 1 ELSE 0 END) DESC
                LIMIT ?
                """, workMapper, viewerId, q, q, q, q, limit);
    }

    public int updateWork(UUID id, String title, String description, String visibility,
                          String readingDirection) {
        return jdbc.update("""
                UPDATE works SET title = ?, description = ?, visibility = ?, reading_direction = ?,
                       updated_at = now()
                WHERE id = ?
                """, title, description, visibility, readingDirection, id);
    }

    public int updateWorkStatus(UUID id, String status) {
        return jdbc.update("UPDATE works SET status = ?, updated_at = now() WHERE id = ?", status, id);
    }

    public int countPublishedChapters(UUID workId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM chapters c
                JOIN chapter_revisions r ON r.id = c.current_revision_id
                WHERE c.work_id = ? AND r.status = 'PUBLISHED'
                """, Integer.class, workId);
        return n == null ? 0 : n;
    }

    // ── chapters ──

    public UUID insertChapter(UUID workId, int ordinal, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO chapters (id, work_id, ordinal, title) VALUES (?, ?, ?, ?)",
                id, workId, ordinal, title);
        return id;
    }

    public Optional<Chapter> findChapter(UUID id) {
        return jdbc.query("SELECT * FROM chapters WHERE id = ?", chapterMapper, id).stream().findFirst();
    }

    public List<Chapter> listChapters(UUID workId) {
        return jdbc.query("SELECT * FROM chapters WHERE work_id = ? ORDER BY ordinal", chapterMapper, workId);
    }

    public int nextChapterOrdinal(UUID workId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(ordinal), 0) FROM chapters WHERE work_id = ?", Integer.class, workId);
        return (max == null ? 0 : max) + 1;
    }

    public List<Chapter> listPublishedChapters(UUID workId) {
        return jdbc.query("""
                SELECT c.* FROM chapters c
                JOIN chapter_revisions r ON r.id = c.current_revision_id
                WHERE c.work_id = ? AND r.status = 'PUBLISHED'
                ORDER BY c.ordinal
                """, chapterMapper, workId);
    }

    // ── chapter revisions ──

    public UUID insertRevision(UUID chapterId, int revisionNo, UUID sourceAssetId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chapter_revisions (id, chapter_id, revision_no, source_asset_id)
                VALUES (?, ?, ?, ?)
                """, id, chapterId, revisionNo, sourceAssetId);
        return id;
    }

    public Optional<ChapterRevision> findRevision(UUID id) {
        return jdbc.query("SELECT * FROM chapter_revisions WHERE id = ?", revisionMapper, id)
                .stream().findFirst();
    }

    public List<ChapterRevision> listRevisions(UUID chapterId) {
        return jdbc.query("SELECT * FROM chapter_revisions WHERE chapter_id = ? ORDER BY revision_no",
                revisionMapper, chapterId);
    }

    public int nextRevisionNo(UUID chapterId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision_no), 0) FROM chapter_revisions WHERE chapter_id = ?",
                Integer.class, chapterId);
        return (max == null ? 0 : max) + 1;
    }

    public int markRevisionStatus(UUID id, String status, String errorCode, String errorMessage) {
        return jdbc.update("""
                UPDATE chapter_revisions SET status = ?, error_code = ?, error_message = ?, updated_at = now()
                WHERE id = ?
                """, status, errorCode, errorMessage, id);
    }

    /** 页序确认：edit_version 条件更新，0 行即冲突（DESIGN 14.3 同款乐观锁语义）。 */
    public boolean confirmPageOrder(UUID revisionId, int expectedEditVersion) {
        return jdbc.update("""
                UPDATE chapter_revisions SET edit_version = edit_version + 1, updated_at = now()
                WHERE id = ? AND edit_version = ?
                """, revisionId, expectedEditVersion) > 0;
    }

    // ── pages ──

    public void insertPage(UUID id, UUID revisionId, int ordinal, UUID imageAssetId,
                           UUID thumbnailAssetId, int width, int height) {
        jdbc.update("""
                INSERT INTO pages (id, chapter_revision_id, ordinal, image_asset_id, thumbnail_asset_id,
                                   width, height)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, revisionId, ordinal, imageAssetId, thumbnailAssetId, width, height);
    }

    public void deletePages(UUID revisionId) {
        jdbc.update("DELETE FROM pages WHERE chapter_revision_id = ?", revisionId);
    }

    public List<Page> listPages(UUID revisionId, int afterOrdinal, int limit) {
        return jdbc.query("""
                SELECT * FROM pages WHERE chapter_revision_id = ? AND ordinal > ?
                ORDER BY ordinal LIMIT ?
                """, pageMapper, revisionId, afterOrdinal, limit);
    }

    public int countPages(UUID revisionId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pages WHERE chapter_revision_id = ?", Integer.class, revisionId);
        return n == null ? 0 : n;
    }

    public Optional<Page> findPageByAsset(UUID assetId) {
        return jdbc.query("""
                SELECT * FROM pages WHERE image_asset_id = ? OR thumbnail_asset_id = ?
                """, pageMapper, assetId, assetId).stream().findFirst();
    }

    /** 页序调换：先整体平移出唯一约束区段，再由调用方落位（避免中途撞 (revision_id, ordinal) 唯一键）。 */
    public void beginReorder(UUID revisionId) {
        jdbc.update("UPDATE pages SET ordinal = ordinal + 1000000 WHERE chapter_revision_id = ?",
                revisionId);
    }

    public void reorderPage(UUID pageId, int ordinal) {
        jdbc.update("UPDATE pages SET ordinal = ? WHERE id = ?", ordinal, pageId);
    }

    public void setRevisionPageCount(UUID revisionId, int pageCount, String manifestSha256) {
        jdbc.update("""
                UPDATE chapter_revisions SET page_count = ?, manifest_sha256 = ?, updated_at = now()
                WHERE id = ?
                """, pageCount, manifestSha256, revisionId);
    }

    /** 发布事务内锁定章节行（DESIGN 10.4），串行化同一章节的发布竞争。 */
    public void lockChapter(UUID chapterId) {
        jdbc.queryForObject("SELECT id FROM chapters WHERE id = ? FOR UPDATE", UUID.class, chapterId);
    }

    /** 原子切换当前版本指针；组合外键保证归属（DESIGN 8.3）。 */
    public void switchCurrentRevision(UUID chapterId, UUID revisionId) {
        jdbc.update("UPDATE chapters SET current_revision_id = ?, updated_at = now() WHERE id = ?",
                revisionId, chapterId);
    }
}
