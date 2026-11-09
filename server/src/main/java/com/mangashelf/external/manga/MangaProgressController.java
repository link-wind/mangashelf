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

/** 外部漫画阅读进度（M18）：服务端保存，跨设备续读。 */
@RestController
@RequestMapping("/api/v1/me")
public class MangaProgressController {

    private final JdbcTemplate jdbc;

    public MangaProgressController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SaveRequest(String source, String mangaId, String chapterId, String chapterLabel,
                              int page, Integer totalPages, String title, String coverUrl) {}

    @PutMapping("/manga-progress")
    public Map<String, Object> save(@AuthUser CurrentUser user, @RequestBody SaveRequest req) {
        if (req.source() == null || req.source().isBlank()
                || req.mangaId() == null || req.mangaId().isBlank()) {
            throw new ApiException(400, "PROGRESS_INVALID", "缺少 source 或 mangaId。");
        }
        String title = req.title() == null || req.title().isBlank() ? "未命名" : req.title();
        jdbc.update("""
                INSERT INTO external_manga_progress
                    (user_id, source, manga_id, chapter_id, chapter_label, page, total_pages, title, cover_url)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, source, manga_id) DO UPDATE SET
                    chapter_id = EXCLUDED.chapter_id, chapter_label = EXCLUDED.chapter_label,
                    page = EXCLUDED.page, total_pages = EXCLUDED.total_pages,
                    title = EXCLUDED.title, cover_url = EXCLUDED.cover_url, updated_at = now()
                """, user.id(), req.source(), req.mangaId(), req.chapterId(), req.chapterLabel(),
                Math.max(0, req.page()), req.totalPages(), title, req.coverUrl());
        return Map.of("saved", true);
    }

    public record ProgressRow(String source, String mangaId, String chapterId, String chapterLabel,
                              int page, Integer totalPages, String title, String coverUrl,
                              Instant updatedAt) {}

    @GetMapping("/manga-progress")
    public List<ProgressRow> recent(@AuthUser CurrentUser user,
                                    @RequestParam(defaultValue = "20") int limit) {
        return jdbc.query("""
                SELECT source, manga_id, chapter_id, chapter_label, page, total_pages,
                       title, cover_url, updated_at
                FROM external_manga_progress WHERE user_id = ?
                ORDER BY updated_at DESC LIMIT ?
                """, (rs, i) -> new ProgressRow(
                        rs.getString("source"), rs.getString("manga_id"),
                        rs.getString("chapter_id"), rs.getString("chapter_label"),
                        rs.getInt("page"), (Integer) rs.getObject("total_pages"),
                        rs.getString("title"), rs.getString("cover_url"),
                        rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()),
                user.id(), Math.min(Math.max(limit, 1), 100));
    }
}
