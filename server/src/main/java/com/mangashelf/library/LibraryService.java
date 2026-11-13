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
