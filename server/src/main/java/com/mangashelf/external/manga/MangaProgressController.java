package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 外部漫画阅读进度（M18）：服务端保存，跨设备续读；版本号防覆盖。 */
@RestController
@RequestMapping("/api/v1/me")
public class MangaProgressController {

    private final JdbcTemplate jdbc;

    public MangaProgressController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SaveRequest(String source, String mangaId, String chapterId, String chapterLabel,
                              int page, Integer totalPages, String title, String coverUrl,
                              Integer expectedVersion) {}

    public record ProgressSnapshot(String source, String mangaId, String chapterId, String chapterLabel,
                                   int page, Integer totalPages, String title, String coverUrl,
                                   int version, Instant updatedAt) {}

    @PutMapping("/manga-progress")
    public ProgressSnapshot save(@AuthUser CurrentUser user, @RequestBody SaveRequest req) {
        if (req.source() == null || req.source().isBlank()
                || req.mangaId() == null || req.mangaId().isBlank()) {
            throw new ApiException(400, "PROGRESS_INVALID", "缺少 source 或 mangaId。");
        }
        if (req.expectedVersion() == null) {
            throw new ApiException(400, "PROGRESS_INVALID", "缺少 expectedVersion（首次写入传 0）。");
        }
        String title = req.title() == null || req.title().isBlank() ? "未命名" : req.title();
        String coverUrl = req.coverUrl() == null || req.coverUrl().isBlank()
                ? resolveCatalogCover(req.source(), req.mangaId(), title)
                : req.coverUrl().trim();
        String chapterLabel = req.chapterLabel() == null || req.chapterLabel().isBlank()
                ? null : req.chapterLabel().trim();
        int page = Math.max(0, req.page());
        int expected = req.expectedVersion();
        List<Integer> versions;
        if (expected == 0) {
            // 首次写入：并发下只有一方能插入，其余按冲突处理。
            versions = jdbc.query("""
                    INSERT INTO external_manga_progress
                        (user_id, source, manga_id, chapter_id, chapter_label, page, total_pages,
                         title, cover_url, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    ON CONFLICT (user_id, source, manga_id) DO NOTHING
                    RETURNING version
                    """, (rs, i) -> rs.getInt(1), user.id(), req.source(), req.mangaId(),
                    req.chapterId(), chapterLabel, page, req.totalPages(), title, coverUrl);
        } else {
            versions = jdbc.query("""
                    UPDATE external_manga_progress
                       SET chapter_id = ?,
                           chapter_label = COALESCE(NULLIF(?, ''), chapter_label),
                           page = ?,
                           total_pages = ?,
                           title = ?,
                           cover_url = COALESCE(NULLIF(?, ''), cover_url),
                           version = version + 1,
                           updated_at = now()
                     WHERE user_id = ? AND source = ? AND manga_id = ? AND version = ?
                    RETURNING version
                    """, (rs, i) -> rs.getInt(1), req.chapterId(), chapterLabel, page,
                    req.totalPages(), title, coverUrl, user.id(), req.source(), req.mangaId(), expected);
        }
        if (versions.isEmpty()) {
            throw new ApiException(409, "PROGRESS_CONFLICT", "进度已被其他设备更新，请刷新。",
                    Map.of("current", current(user, req.source(), req.mangaId())
                            .orElse(new ProgressSnapshot(req.source(), req.mangaId(), null, null,
                                    page, req.totalPages(), title, req.coverUrl(), 0, null))));
        }
        return current(user, req.source(), req.mangaId()).orElseThrow();
    }

    private Optional<ProgressSnapshot> current(CurrentUser user, String source, String mangaId) {
        return jdbc.query("""
                SELECT source, manga_id, chapter_id, chapter_label, page, total_pages,
                       title, cover_url, version, updated_at
                  FROM external_manga_progress
                 WHERE user_id = ? AND source = ? AND manga_id = ?
                """, (rs, i) -> new ProgressSnapshot(rs.getString("source"), rs.getString("manga_id"),
                        rs.getString("chapter_id"), rs.getString("chapter_label"), rs.getInt("page"),
                        (Integer) rs.getObject("total_pages"), rs.getString("title"),
                        rs.getString("cover_url"), rs.getInt("version"),
                        rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()),
                user.id(), source, mangaId).stream().findFirst();
    }

    public record ProgressRow(String source, String mangaId, String chapterId, String chapterLabel,
                              int page, Integer totalPages, String title, String coverUrl,
                              int version, Instant updatedAt) {}

    @GetMapping("/manga-progress")
    public List<ProgressRow> recent(@AuthUser CurrentUser user,
                                    @RequestParam(defaultValue = "20") int limit) {
        return jdbc.query("""
                SELECT p.source, p.manga_id, p.chapter_id, p.chapter_label, p.page, p.total_pages,
                       p.title,
                       COALESCE(
                           NULLIF(p.cover_url, ''),
                           NULLIF(mc.cover_url, ''),
                           (SELECT NULLIF(mc2.cover_url, '')
                              FROM manga_catalog_items mc2
                             WHERE mc2.name = p.title AND mc2.cover_url <> ''
                             ORDER BY mc2.synced_at DESC LIMIT 1)
                       ) AS cover_url,
                       p.version, p.updated_at
                  FROM external_manga_progress p
                  LEFT JOIN manga_catalog_items mc
                    ON mc.source = p.source
                   AND mc.source_item_id = regexp_replace(p.manga_id, '^mc', '')
                 WHERE p.user_id = ?
                 ORDER BY p.updated_at DESC LIMIT ?
                """, (rs, i) -> new ProgressRow(
                        rs.getString("source"), rs.getString("manga_id"),
                        rs.getString("chapter_id"), rs.getString("chapter_label"),
                        rs.getInt("page"), (Integer) rs.getObject("total_pages"),
                        rs.getString("title"), rs.getString("cover_url"), rs.getInt("version"),
                        rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()),
                user.id(), Math.min(Math.max(limit, 1), 100));
    }

    private String resolveCatalogCover(String source, String mangaId, String title) {
        List<String> found = jdbc.query("""
                SELECT cover_url
                  FROM manga_catalog_items
                 WHERE ((source = ? AND source_item_id = regexp_replace(?, '^mc', '')) OR name = ?)
                   AND cover_url <> ''
                 ORDER BY (source = ? AND source_item_id = regexp_replace(?, '^mc', '')) DESC, synced_at DESC
                 LIMIT 1
                """, (rs, i) -> rs.getString("cover_url"), source, mangaId, title, source, mangaId);
        return found.isEmpty() ? null : found.get(0);
    }
}
