package com.mangashelf.animation;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 动画模块显式 SQL。跨表页码范围校验在服务层事务内完成（DESIGN 8.3）。 */
@Repository
public class AnimationRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<Animation> animationMapper = new DataClassRowMapper<>(Animation.class);
    private final RowMapper<AnimationRevision> revisionMapper = new DataClassRowMapper<>(AnimationRevision.class);
    private final RowMapper<AnimationSource> sourceMapper = new DataClassRowMapper<>(AnimationSource.class);

    public AnimationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── animations ──

    public UUID insertAnimation(UUID ownerId, String title, String description) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO animations (id, owner_id, title, description) VALUES (?, ?, ?, ?)",
                id, ownerId, title, description);
        return id;
    }

    public Optional<Animation> findAnimation(UUID id) {
        return jdbc.query("SELECT * FROM animations WHERE id = ?", animationMapper, id).stream().findFirst();
    }

    public List<Animation> listVisible(UUID viewerId, String titleFilter, java.time.OffsetDateTime beforeCreatedAt,
                                       UUID beforeId, int limit) {
        String filter = titleFilter == null || titleFilter.isBlank()
                ? ""
                : "AND title ILIKE ? ESCAPE '\\'";
        Object[] args = titleFilter == null || titleFilter.isBlank()
                ? new Object[]{viewerId, beforeCreatedAt, beforeId, limit}
                : new Object[]{viewerId, likeEscape(titleFilter), beforeCreatedAt, beforeId, limit};
        return jdbc.query("""
                SELECT * FROM animations
                WHERE (owner_id = ? OR (visibility = 'PUBLIC' AND status = 'PUBLISHED'))
                %s
                AND (created_at, id) < (?, ?)
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """.formatted(filter), animationMapper, args);
    }

    public int updateAnimationStatus(UUID id, String status) {
        return jdbc.update("UPDATE animations SET status = ?, updated_at = now() WHERE id = ?", status, id);
    }

    public void lockAnimation(UUID animationId) {
        jdbc.queryForObject("SELECT id FROM animations WHERE id = ? FOR UPDATE", UUID.class, animationId);
    }

    public void switchCurrentRevision(UUID animationId, UUID revisionId) {
        jdbc.update("UPDATE animations SET current_revision_id = ?, updated_at = now() WHERE id = ?",
                revisionId, animationId);
    }

    // ── animation_revisions ──

    public UUID insertRevision(UUID animationId, int revisionNo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO animation_revisions (id, animation_id, revision_no) VALUES (?, ?, ?)",
                id, animationId, revisionNo);
        return id;
    }

    public Optional<AnimationRevision> findRevision(UUID id) {
        return jdbc.query("SELECT * FROM animation_revisions WHERE id = ?", revisionMapper, id)
                .stream().findFirst();
    }

    public List<AnimationRevision> listRevisions(UUID animationId) {
        return jdbc.query("SELECT * FROM animation_revisions WHERE animation_id = ? ORDER BY revision_no",
                revisionMapper, animationId);
    }

    public int nextRevisionNo(UUID animationId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision_no), 0) FROM animation_revisions WHERE animation_id = ?",
                Integer.class, animationId);
        return (max == null ? 0 : max) + 1;
    }

    public void setOrigin(UUID revisionId, String originClient, String originRunRef) {
        jdbc.update("UPDATE animation_revisions SET origin_client = ?, origin_run_ref = ?, updated_at = now() WHERE id = ?",
                originClient, originRunRef, revisionId);
    }

    /** 按接入来源键查草稿（限定 owner，DESIGN 17.3 幂等以用户+接入类型+键为界）。 */
    public Optional<AnimationRevision> findRevisionByOrigin(UUID ownerId, String originClient,
                                                            String originRunRef) {
        return jdbc.query("""
                SELECT ar.* FROM animation_revisions ar
                JOIN animations a ON a.id = ar.animation_id
                WHERE a.owner_id = ? AND ar.origin_client = ? AND ar.origin_run_ref = ?
                """, revisionMapper, ownerId, originClient, originRunRef).stream().findFirst();
    }

    public int markRevisionStatus(UUID id, String status, String errorCode, String errorMessage) {
        return jdbc.update("""
                UPDATE animation_revisions SET status = ?, error_code = ?, error_message = ?, updated_at = now()
                WHERE id = ?
                """, status, errorCode, errorMessage, id);
    }

    public void bindAssetsAndMedia(UUID id, UUID videoAssetId, UUID posterAssetId, UUID subtitleAssetId,
                                   Long durationMs, Integer width, Integer height,
                                   String videoCodec, String audioCodec, Boolean hasAudio,
                                   String sourceManifestSha256) {
        jdbc.update("""
                UPDATE animation_revisions
                SET video_asset_id = ?, poster_asset_id = ?, subtitle_asset_id = ?,
                    duration_ms = ?, video_width = ?, video_height = ?, video_codec = ?,
                    audio_codec = ?, has_audio = ?, source_manifest_sha256 = ?, updated_at = now()
                WHERE id = ?
                """, videoAssetId, posterAssetId, subtitleAssetId, durationMs, width, height,
                videoCodec, audioCodec, hasAudio, sourceManifestSha256, id);
    }

    // ── animation_sources ──

    public void replaceSources(UUID revisionId, List<AnimationSource> sources) {
        jdbc.update("DELETE FROM animation_sources WHERE animation_revision_id = ?", revisionId);
        for (AnimationSource s : sources) {
            jdbc.update("""
                    INSERT INTO animation_sources (id, animation_revision_id, chapter_revision_id,
                                                   page_start, page_end, ordinal)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, s.id(), s.animationRevisionId(), s.chapterRevisionId(),
                    s.pageStart(), s.pageEnd(), s.ordinal());
        }
    }

    public List<AnimationSource> listSources(UUID revisionId) {
        return jdbc.query("SELECT * FROM animation_sources WHERE animation_revision_id = ? ORDER BY ordinal",
                sourceMapper, revisionId);
    }

    /** 页级关联（DESIGN 8.4：先按章节版本缩小范围再判断页码）。 */
    public List<PageAdaptation> findPageAdaptations(UUID chapterRevisionId, int page) {
        String sql = """
                SELECT a.id AS animation_id, a.title, a.visibility, a.status AS animation_status,
                       ar.id AS revision_id, ar.status AS revision_status,
                       s.page_start, s.page_end
                FROM animation_sources s
                JOIN animation_revisions ar ON ar.id = s.animation_revision_id
                JOIN animations a ON a.id = ar.animation_id
                WHERE s.chapter_revision_id = ?
                  AND s.page_start <= ? AND s.page_end >= ?
                  AND ar.status = 'PUBLISHED'
                  AND a.status = 'PUBLISHED'
                ORDER BY s.ordinal
                """;
        return jdbc.query(sql, (rs, i) -> new PageAdaptation(
                rs.getObject("animation_id", UUID.class),
                rs.getString("title"),
                rs.getString("visibility"),
                rs.getObject("revision_id", UUID.class),
                rs.getInt("page_start"), rs.getInt("page_end")),
                chapterRevisionId, page, page);
    }

    public record PageAdaptation(UUID animationId, String title, String visibility,
                                 UUID revisionId, int pageStart, int pageEnd) {
    }

    /** 观看页来源信息（DESIGN 4.1：显示来源作品、章节与页码范围，提供回到原作入口）。 */
    public List<SourceWithWork> listSourcesWithWork(UUID revisionId) {
        String sql = """
                SELECT s.chapter_revision_id, s.page_start, s.page_end, s.ordinal,
                       c.id AS chapter_id, c.ordinal AS chapter_ordinal, c.title AS chapter_title,
                       w.id AS work_id, w.title AS work_title
                FROM animation_sources s
                JOIN chapter_revisions r ON r.id = s.chapter_revision_id
                JOIN chapters c ON c.id = r.chapter_id
                JOIN works w ON w.id = c.work_id
                WHERE s.animation_revision_id = ?
                ORDER BY s.ordinal
                """;
        return jdbc.query(sql, (rs, i) -> new SourceWithWork(
                rs.getObject("chapter_revision_id", UUID.class),
                rs.getInt("page_start"), rs.getInt("page_end"), rs.getInt("ordinal"),
                rs.getObject("chapter_id", UUID.class),
                rs.getInt("chapter_ordinal"), rs.getString("chapter_title"),
                rs.getObject("work_id", UUID.class), rs.getString("work_title")), revisionId);
    }

    public record SourceWithWork(UUID chapterRevisionId, int pageStart, int pageEnd, int ordinal,
                                 UUID chapterId, int chapterOrdinal, String chapterTitle,
                                 UUID workId, String workTitle) {
    }

    /** 作品级"有动画"判定（搜索过滤用）：PUBLISHED 动画的来源引用该作品的章节。 */
    public int countPublishedForWork(UUID workId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(DISTINCT a.id) FROM animation_sources s
                JOIN animation_revisions ar ON ar.id = s.animation_revision_id
                JOIN animations a ON a.id = ar.animation_id
                JOIN chapters c ON c.id = s.chapter_revision_id
                WHERE c.work_id = ? AND ar.status = 'PUBLISHED' AND a.status = 'PUBLISHED'
                """, Integer.class, workId);
        return n == null ? 0 : n;
    }

    // ── watch progress（DESIGN 14.3）──

    /** 条件更新：版本匹配才覆盖并递增；返回新版本号，空即冲突。 */
    public Optional<Integer> upsertWatchProgress(UUID userId, UUID animationId, UUID revisionId,
                                                 long positionMs, int expectedVersion) {
        List<Integer> versions = jdbc.query("""
                INSERT INTO watch_progress (user_id, animation_id, animation_revision_id, position_ms,
                                            version, updated_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (user_id, animation_id) DO UPDATE
                SET animation_revision_id = EXCLUDED.animation_revision_id,
                    position_ms = EXCLUDED.position_ms,
                    version = watch_progress.version + 1,
                    updated_at = now()
                WHERE watch_progress.version = ?
                RETURNING version
                """, (rs, i) -> rs.getInt("version"),
                userId, animationId, revisionId, positionMs, expectedVersion + 1, expectedVersion);
        return versions.stream().findFirst();
    }

    public record WatchProgressRow(UUID animationId, UUID revisionId, long positionMs, int version) {
    }

    public Optional<WatchProgressRow> findWatchProgress(UUID userId, UUID animationId) {
        return jdbc.query("""
                SELECT animation_id, animation_revision_id, position_ms, version
                FROM watch_progress WHERE user_id = ? AND animation_id = ?
                """, (rs, i) -> new WatchProgressRow(rs.getObject("animation_id", UUID.class),
                rs.getObject("animation_revision_id", UUID.class), rs.getLong("position_ms"),
                rs.getInt("version")), userId, animationId).stream().findFirst();
    }

    private static String likeEscape(String s) {
        return "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
