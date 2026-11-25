package com.mangashelf.catalog;

import com.mangashelf.config.ApiException;
import com.mangashelf.identity.CurrentUser;
import com.mangashelf.storage.PresignService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** 漫画专区（M15）：索引与详情；封面来自章节首页缩略图。 */
@Service
public class MangaService {

    private final MangaRepository manga;
    private final CatalogService catalogService;
    private final PresignService presign;

    public MangaService(MangaRepository manga, CatalogService catalogService, PresignService presign) {
        this.manga = manga;
        this.catalogService = catalogService;
        this.presign = presign;
    }

    public record MangaCard(UUID id, String title, String description, String visibility,
                            int chapterCount, Integer latestOrdinal, String latestTitle,
                            String coverUrl, Instant updatedAt) {}

    public record MangaListPage(List<MangaCard> items, String nextCursor) {}

    public MangaListPage list(CurrentUser viewer, String cursor, int limit, String q) {
        Cursor c = decodeCursor(cursor);
        int effectiveLimit = Math.min(Math.max(limit, 1), 48);
        List<MangaRepository.MangaRow> rows =
                manga.listVisible(viewer.id(), q, c.updatedAt(), c.id(), effectiveLimit);
        List<MangaCard> items = rows.stream().map(r -> new MangaCard(
                r.id(), r.title(), r.description(), r.visibility(), r.chapterCount(),
                r.latestOrdinal(), r.latestTitle(),
                r.coverKey() == null ? null
                        : presign.presignGet(r.coverKey(), PresignService.MediaCacheProfile.IMMUTABLE_IMAGE).url(),
                r.updatedAt())).toList();
        String next = items.size() == effectiveLimit
                ? encodeCursor(rows.getLast().updatedAt(), rows.getLast().id())
                : null;
        return new MangaListPage(items, next);
    }

    public record ChapterRow(UUID id, int ordinal, String title, UUID currentRevisionId,
                             Integer readPageOrdinal) {}

    public record ContinueInfo(UUID chapterId, UUID revisionId, int pageOrdinal) {}

    public record MangaDetail(Work work, List<ChapterRow> chapters, boolean owner,
                              String coverUrl, ContinueInfo continueReading) {}

    public MangaDetail detail(CurrentUser viewer, UUID workId) {
        CatalogService.WorkView view = catalogService.workView(viewer, workId);
        Work work = view.work();
        boolean owner = view.owner();
        var progress = manga.progressByChapter(viewer.id(), workId);
        List<ChapterRow> chapters = view.chapters().stream()
                .map(c -> new ChapterRow(c.id(), c.ordinal(), c.title(), c.currentRevisionId(),
                        progress.get(c.id())))
                .toList();
        String coverUrl = manga.coverKey(workId)
                .map(k -> presign.presignGet(k, PresignService.MediaCacheProfile.IMMUTABLE_IMAGE).url())
                .orElse(null);
        ContinueInfo cont = manga.continueTarget(viewer.id(), workId)
                .map(t -> new ContinueInfo(t.chapterId(), t.revisionId(), t.pageOrdinal()))
                .orElse(null);
        return new MangaDetail(work, chapters, owner, coverUrl, cont);
    }

    // ── 游标 ──

    private record Cursor(Instant updatedAt, UUID id) {}

    private static String encodeCursor(Instant t, UUID id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((t.toString() + "|" + id).getBytes());
    }

    private static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor(Instant.parse("9999-12-31T23:59:59Z"),
                    new UUID(Long.MAX_VALUE, Long.MAX_VALUE));
        }
        try {
            String s = new String(Base64.getUrlDecoder().decode(cursor));
            int cut = s.lastIndexOf('|');
            return new Cursor(Instant.parse(s.substring(0, cut)), UUID.fromString(s.substring(cut + 1)));
        } catch (Exception e) {
            throw new ApiException(400, "BAD_CURSOR", "分页游标无效。");
        }
    }
}
