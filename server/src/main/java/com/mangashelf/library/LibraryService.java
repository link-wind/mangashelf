package com.mangashelf.library;

import com.mangashelf.catalog.CatalogService;
import com.mangashelf.catalog.Chapter;
import com.mangashelf.config.ApiException;
import com.mangashelf.identity.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 书架与进度业务（DESIGN 4 我的书架、14.3 冲突语义）。 */
@Service
public class LibraryService {

    private final LibraryRepository library;
    private final CatalogService catalog;

    public LibraryService(LibraryRepository library, CatalogService catalog) {
        this.library = library;
        this.catalog = catalog;
    }

    public record ShelfItem(UUID workId, String title, String workStatus, String visibility,
                            java.time.Instant entryCreatedAt, UUID lastChapterId,
                            Integer lastPageOrdinal, java.time.Instant lastReadAt) {
    }

    public record ShelfView(List<ShelfItem> items, List<ContinueRow> continueReading) {
    }

    public record ContinueRow(UUID chapterId, UUID revisionId, int pageOrdinal,
                              UUID workId, String workTitle) {
    }

    /** 重复加入返回既有条目，不重复创建（library-progress 规格）。 */
    public boolean addToShelf(CurrentUser user, UUID workId) {
        catalog.requireVisibleWork(user, workId);
        return library.addEntry(user.id(), workId);
    }

    public void removeFromShelf(CurrentUser user, UUID workId) {
        library.removeEntry(user.id(), workId);
    }

    public ShelfView shelf(CurrentUser user) {
        List<ShelfItem> items = library.listShelf(user.id()).stream()
                .map(e -> new ShelfItem(e.workId(), e.title(), e.workStatus(), e.visibility(),
                        e.entryCreatedAt(), e.lastChapterId(), e.lastPageOrdinal(), e.lastReadAt()))
                .toList();
        List<ContinueRow> continueReading = library.listRecentProgress(user.id(), 5).stream()
                .map(r -> new ContinueRow(r.chapterId(), r.revisionId(), r.pageOrdinal(),
                        r.workId(), r.workTitle()))
                .toList();
        return new ShelfView(items, continueReading);
    }

    public record ProgressSnapshot(UUID chapterId, UUID revisionId, int pageOrdinal, int version) {
    }

    public record SaveProgressRequest(UUID chapterRevisionId, int pageOrdinal, int expectedVersion) {
    }

    public ProgressSnapshot progress(CurrentUser user, UUID chapterId) {
        return library.findProgress(user.id(), chapterId)
                .map(p -> new ProgressSnapshot(p.chapterId(), p.revisionId(), p.pageOrdinal(), p.version()))
                .orElseThrow(() -> new ApiException(404, "PROGRESS_NOT_FOUND", "没有阅读进度。"));
    }

    /** 进度写入（DESIGN 14.3）：版本不匹配返回 409 与当前快照，允许前后跳页。 */
    public ProgressSnapshot saveProgress(CurrentUser user, UUID chapterId, SaveProgressRequest request) {
        library.requireChapterExists(chapterId);
        Chapter chapter = catalogChapter(user, chapterId);
        // 进度只对可读内容记录：owner 或满足公开条件（与页面清单同一判定）
        catalog.requireVisibleWork(user, chapter.workId());
        return library.upsertProgress(user.id(), chapterId, request.chapterRevisionId(),
                request.pageOrdinal(), request.expectedVersion())
                .map(v -> new ProgressSnapshot(chapterId, request.chapterRevisionId(),
                        request.pageOrdinal(), v))
                .orElseGet(() -> {
                    LibraryRepository.ProgressRow current =
                            library.findProgress(user.id(), chapterId).orElseThrow();
                    throw new ApiException(409, "PROGRESS_CONFLICT", "进度已被其他设备更新，请刷新。",
                            java.util.Map.of("current", new ProgressSnapshot(current.chapterId(),
                                    current.revisionId(), current.pageOrdinal(), current.version())));
                });
    }

    private Chapter catalogChapter(CurrentUser user, UUID chapterId) {
        return catalog.chapterView(user, chapterId).chapter();
    }
}
