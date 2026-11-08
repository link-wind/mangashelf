package com.mangashelf.catalog;

import com.mangashelf.config.ApiException;
import com.mangashelf.identity.CurrentUser;
import com.mangashelf.ingestion.Job;
import com.mangashelf.ingestion.JobCreationService;
import com.mangashelf.storage.AssetRepository;
import com.mangashelf.storage.PresignService;
import com.mangashelf.storage.UploadSession;
import com.mangashelf.storage.UploadSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 目录业务（DESIGN 3.2/3.3、9、10.3、10.4、14.1）。
 * 所有权、可见性、发布状态一起判定；公开读取要求 PUBLIC + PUBLISHED + 版本 PUBLISHED。
 */
@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    public static final int MAX_PAGE_LIMIT = 50;

    private final CatalogRepository catalog;
    private final UploadSessionRepository sessions;
    private final AssetRepository assets;
    private final JobCreationService jobCreation;
    private final PresignService presign;
    private final TransactionTemplate tx;

    public CatalogService(CatalogRepository catalog, UploadSessionRepository sessions,
                          AssetRepository assets, JobCreationService jobCreation,
                          PresignService presign, TransactionTemplate tx) {
        this.catalog = catalog;
        this.sessions = sessions;
        this.assets = assets;
        this.jobCreation = jobCreation;
        this.presign = presign;
        this.tx = tx;
    }

    // ── 作品 ──

    public Work createWork(CurrentUser user, String title, String description) {
        UUID id = catalog.insertWork(user.id(), title, description);
        log.info("work {} created by {}", id, user.id());
        return catalog.findWork(id).orElseThrow();
    }

    /** 可见性判定统一入口（DESIGN 3.3：列表、详情、页面、签名授权共用）。 */
    public boolean isVisible(CurrentUser viewer, Work work) {
        return work.ownerId().equals(viewer.id()) || viewer.isAdmin()
                || (Work.VISIBILITY_PUBLIC.equals(work.visibility())
                    && Work.STATUS_PUBLISHED.equals(work.status()));
    }

    public Work requireVisibleWork(CurrentUser viewer, UUID workId) {
        Work work = catalog.findWork(workId).orElseThrow(() ->
                new ApiException(404, "WORK_NOT_FOUND", "作品不存在。"));
        if (!isVisible(viewer, work)) {
            // 按策略不暴露存在性（DESIGN 16.2）
            throw new ApiException(404, "WORK_NOT_FOUND", "作品不存在。");
        }
        return work;
    }

    public record WorkView(Work work, List<Chapter> chapters, boolean owner) {
    }

    public WorkView workView(CurrentUser viewer, UUID workId) {
        Work work = requireVisibleWork(viewer, workId);
        boolean owner = work.ownerId().equals(viewer.id()) || viewer.isAdmin();
        // 非所有者只看到已发布章节；所有者看到全部章节（含未发布）
        List<Chapter> chapters = owner
                ? catalog.listChapters(workId)
                : catalog.listPublishedChapters(workId);
        return new WorkView(work, chapters, owner);
    }

    public record WorkListPage(List<Work> items, String nextCursor) {
    }

    public WorkListPage listWorks(CurrentUser viewer, String cursor, int limit, String q) {
        Cursor c = decodeCursor(cursor);
        int effectiveLimit = Math.min(limit, MAX_PAGE_LIMIT);
        List<Work> items = catalog.listVisible(viewer.id(), q, c.createdAt(), c.id(), effectiveLimit);
        String next = items.size() == effectiveLimit
                ? encodeCursor(items.getLast().createdAt(), items.getLast().id())
                : null;
        return new WorkListPage(items, next);
    }

    public record PatchWorkRequest(String title, String description, String visibility,
                                   String readingDirection) {
    }

    public Work patchWork(CurrentUser user, UUID workId, PatchWorkRequest request) {
        Work work = requireOwnedWork(user, workId);
        String title = request.title() != null ? request.title() : work.title();
        String description = request.description() != null ? request.description() : work.description();
        String visibility = request.visibility() != null ? request.visibility() : work.visibility();
        if (!Work.VISIBILITY_PRIVATE.equals(visibility) && !Work.VISIBILITY_PUBLIC.equals(visibility)) {
            throw new ApiException(400, "INVALID_VISIBILITY", "visibility 只能为 PRIVATE 或 PUBLIC。");
        }
        String direction = request.readingDirection() != null
                ? request.readingDirection() : work.readingDirection();
        if (!"rtl".equals(direction) && !"ltr".equals(direction)) {
            throw new ApiException(400, "INVALID_READING_DIRECTION", "readingDirection 只能为 rtl 或 ltr。");
        }
        catalog.updateWork(workId, title, description, visibility, direction);
        return catalog.findWork(workId).orElseThrow();
    }

    /** 发布作品：要求至少一个已发布章节版本（DESIGN 2.1 能读、9 容器状态）。 */
    public Work publishWork(CurrentUser user, UUID workId) {
        requireOwnedWork(user, workId);
        if (catalog.countPublishedChapters(workId) == 0) {
            throw new ApiException(409, "NO_PUBLISHED_CONTENT", "作品还没有已发布的章节版本。");
        }
        catalog.updateWorkStatus(workId, Work.STATUS_PUBLISHED);
        return catalog.findWork(workId).orElseThrow();
    }

    public Work withdrawWork(CurrentUser user, UUID workId) {
        requireOwnedWork(user, workId);
        catalog.updateWorkStatus(workId, Work.STATUS_WITHDRAWN);
        return catalog.findWork(workId).orElseThrow();
    }

    public Work requireOwnedWork(CurrentUser user, UUID workId) {
        Work work = catalog.findWork(workId).orElseThrow(() ->
                new ApiException(404, "WORK_NOT_FOUND", "作品不存在。"));
        if (!work.ownerId().equals(user.id()) && !user.isAdmin()) {
            throw new ApiException(403, "FORBIDDEN", "只有所有者可以执行该操作。");
        }
        return work;
    }

    // ── 章节 ──

    public Chapter createChapter(CurrentUser user, UUID workId, String title) {
        requireOwnedWork(user, workId);
        UUID id = catalog.insertChapter(workId, catalog.nextChapterOrdinal(workId), title);
        return catalog.findChapter(id).orElseThrow();
    }

    public record ChapterView(Chapter chapter, Work work, UUID currentRevisionId,
                              List<RevisionView> revisions) {
    }

    public record RevisionView(UUID id, int revisionNo, String status, int pageCount,
                               Instant createdAt) {
    }

    public ChapterView chapterView(CurrentUser viewer, UUID chapterId) {
        Chapter chapter = catalog.findChapter(chapterId).orElseThrow(() ->
                new ApiException(404, "CHAPTER_NOT_FOUND", "章节不存在。"));
        Work work = requireVisibleWork(viewer, chapter.workId());
        boolean owner = work.ownerId().equals(viewer.id()) || viewer.isAdmin();
        List<RevisionView> revisions = owner
                ? catalog.listRevisions(chapterId).stream()
                        .map(r -> new RevisionView(r.id(), r.revisionNo(), r.status(), r.pageCount(),
                                r.createdAt()))
                        .toList()
                : List.of();
        return new ChapterView(chapter, work, chapter.currentRevisionId(), revisions);
    }

    // ── 章节版本 ──

    public record CreateRevisionRequest(UUID uploadId) {
    }

    public record RevisionCreated(UUID revisionId, UUID jobId) {
    }

    /** 从已核验上传创建章节版本（DESIGN 10.2：上传与版本创建分离）。 */
    public RevisionCreated createRevision(CurrentUser user, UUID chapterId, UUID uploadId) {
        Chapter chapter = catalog.findChapter(chapterId).orElseThrow(() ->
                new ApiException(404, "CHAPTER_NOT_FOUND", "章节不存在。"));
        requireOwnedWork(user, chapter.workId());
        UploadSession session = sessions.findById(uploadId).orElseThrow(() ->
                new ApiException(404, "UPLOAD_NOT_FOUND", "上传会话不存在。"));
        if (!session.ownerId().equals(user.id()) && !user.isAdmin()) {
            throw new ApiException(404, "UPLOAD_NOT_FOUND", "上传会话不存在。");
        }
        if (!UploadSession.STATUS_VERIFIED.equals(session.status())) {
            throw new ApiException(409, "UPLOAD_NOT_VERIFIED", "上传尚未通过核验。");
        }
        int revisionNo = catalog.nextRevisionNo(chapterId);
        return tx.execute(status -> {
            UUID revisionId = catalog.insertRevision(chapterId, revisionNo, session.assetId());
            UUID jobId = jobCreation.create(Job.KIND_CHAPTER_PARSE, user.id(), null,
                    revisionId, null, null, null, "parse:" + revisionId);
            log.info("revision {} (#{}) created for chapter {} from upload {}",
                    revisionId, revisionNo, chapterId, uploadId);
            return new RevisionCreated(revisionId, jobId);
        });
    }

    public ChapterRevision requireOwnedRevision(CurrentUser user, UUID revisionId) {
        ChapterRevision revision = catalog.findRevision(revisionId).orElseThrow(() ->
                new ApiException(404, "REVISION_NOT_FOUND", "章节版本不存在。"));
        Chapter chapter = catalog.findChapter(revision.chapterId()).orElseThrow();
        Work work = catalog.findWork(chapter.workId()).orElseThrow();
        if (!work.ownerId().equals(user.id()) && !user.isAdmin()) {
            throw new ApiException(404, "REVISION_NOT_FOUND", "章节版本不存在。");
        }
        return revision;
    }

    /**
     * 读者可读校验（DESIGN 3.3/4.1）：owner/admin，或（作品 PUBLIC+PUBLISHED 且版本 PUBLISHED）。
     * 页级关联查询与阅读清单共用同一判定。
     */
    public ChapterRevision requireReadableRevision(CurrentUser viewer, UUID revisionId) {
        ChapterRevision revision = catalog.findRevision(revisionId).orElseThrow(() ->
                new ApiException(404, "REVISION_NOT_FOUND", "章节版本不存在。"));
        Chapter chapter = catalog.findChapter(revision.chapterId()).orElseThrow();
        Work work = catalog.findWork(chapter.workId()).orElseThrow();
        boolean owner = work.ownerId().equals(viewer.id()) || viewer.isAdmin();
        boolean readerVisible = Work.VISIBILITY_PUBLIC.equals(work.visibility())
                && Work.STATUS_PUBLISHED.equals(work.status())
                && ChapterRevision.STATUS_PUBLISHED.equals(revision.status());
        if (!owner && !readerVisible) {
            throw new ApiException(404, "REVISION_NOT_FOUND", "章节版本不存在。");
        }
        return revision;
    }

    // ── 页序确认（DESIGN 10.3）──

    public record PageOrderRequest(List<UUID> orderedPageIds, int expectedEditVersion) {
    }

    public ChapterRevision confirmPageOrder(CurrentUser user, UUID revisionId,
                                            PageOrderRequest request) {
        ChapterRevision revision = requireOwnedRevision(user, revisionId);
        if (!ChapterRevision.STATUS_READY.equals(revision.status())) {
            throw new ApiException(409, "INVALID_STATE", "只有 READY 版本可以确认页序。");
        }
        List<Page> pages = catalog.listPages(revisionId, 0, Integer.MAX_VALUE);
        boolean sameSet = pages.size() == request.orderedPageIds().size()
                && pages.stream().map(Page::id).collect(Collectors.toSet())
                    .equals(new HashSet<>(request.orderedPageIds()));
        if (!sameSet) {
            throw new ApiException(400, "PAGE_SET_MISMATCH", "页序清单与版本页面不一致。");
        }
        return tx.execute(status -> {
            if (!catalog.confirmPageOrder(revisionId, request.expectedEditVersion())) {
                ChapterRevision current = catalog.findRevision(revisionId).orElseThrow();
                throw new ApiException(409, "EDIT_VERSION_CONFLICT", "页序确认冲突，请刷新后重试。",
                        Map.of("edit_version", current.editVersion()));
            }
            catalog.beginReorder(revisionId);
            for (int i = 0; i < request.orderedPageIds().size(); i++) {
                catalog.reorderPage(request.orderedPageIds().get(i), i + 1);
            }
            return catalog.findRevision(revisionId).orElseThrow();
        });
    }

    // ── 发布（DESIGN 10.4）──

    public ChapterRevision publishRevision(CurrentUser user, UUID revisionId) {
        requireOwnedRevision(user, revisionId);
        ChapterRevision revision = catalog.findRevision(revisionId).orElseThrow();
        // 事务内锁定章节行，串行化同一章节的发布竞争；组合外键保证指针归属正确
        return tx.execute(status -> {
            catalog.lockChapter(revision.chapterId());
            ChapterRevision fresh = catalog.findRevision(revisionId).orElseThrow();
            if (!ChapterRevision.STATUS_READY.equals(fresh.status())) {
                throw new ApiException(409, "INVALID_STATE",
                        "版本当前状态不允许发布（期望 READY）。当前：" + fresh.status());
            }
            catalog.markRevisionStatus(revisionId, ChapterRevision.STATUS_PUBLISHED, null, null);
            catalog.switchCurrentRevision(revision.chapterId(), revisionId);
            log.info("revision {} published for chapter {}", revisionId, revision.chapterId());
            return catalog.findRevision(revisionId).orElseThrow();
        });
    }

    // ── 页面清单（DESIGN 14.1）──

    public record PageView(UUID id, int ordinal, int width, int height,
                           String imageUrl, String thumbnailUrl, Instant imageExpiresAt) {
    }

    public record PagesPage(UUID revisionId, String status, int pageCount, int editVersion,
                            String nextCursor, List<PageView> pages) {
    }

    public PagesPage pages(CurrentUser viewer, UUID revisionId, String cursor, int limit) {
        ChapterRevision revision = catalog.findRevision(revisionId).orElseThrow(() ->
                new ApiException(404, "REVISION_NOT_FOUND", "章节版本不存在。"));
        Chapter chapter = catalog.findChapter(revision.chapterId()).orElseThrow();
        Work work = catalog.findWork(chapter.workId()).orElseThrow();
        boolean owner = work.ownerId().equals(viewer.id()) || viewer.isAdmin();
        boolean readerVisible = Work.VISIBILITY_PUBLIC.equals(work.visibility())
                && Work.STATUS_PUBLISHED.equals(work.status())
                && ChapterRevision.STATUS_PUBLISHED.equals(revision.status());
        if (!owner && !readerVisible) {
            throw new ApiException(404, "REVISION_NOT_FOUND", "章节版本不存在。");
        }
        int after = decodeOrdinal(cursor);
        int effectiveLimit = Math.min(limit, MAX_PAGE_LIMIT);
        List<Page> pages = catalog.listPages(revisionId, after, effectiveLimit);
        List<PageView> views = pages.stream().map(p -> {
            var image = presign.presignGet(objectKeyOfAsset(p.imageAssetId()));
            String thumb = p.thumbnailAssetId() == null ? null
                    : presign.presignGet(objectKeyOfAsset(p.thumbnailAssetId())).url();
            return new PageView(p.id(), p.ordinal(), p.width(), p.height(),
                    image.url(), thumb, image.expiresAt());
        }).toList();
        String next = pages.size() == effectiveLimit
                ? encodeOrdinal(pages.getLast().ordinal()) : null;
        return new PagesPage(revisionId, revision.status(), revision.pageCount(),
                revision.editVersion(), next, views);
    }

    private String objectKeyOfAsset(UUID assetId) {
        return assets.findObjectKey(assetId).orElseThrow(() ->
                new ApiException(404, "ASSET_NOT_FOUND", "页面资产不存在。"));
    }

    // ── 游标（created_at, id 稳定键；页序游标为 ordinal）──

    public record Cursor(Instant createdAt, UUID id) {
    }

    public static String encodeCursor(Instant createdAt, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor(Instant.parse("9999-12-31T00:00:00Z"), new UUID(0, 0));
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8).split("\\|", 2);
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (Exception e) {
            throw new ApiException(400, "INVALID_CURSOR", "游标不合法。");
        }
    }

    static int decodeOrdinal(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(cursor);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "INVALID_CURSOR", "游标不合法。");
        }
    }

    static String encodeOrdinal(int ordinal) {
        return String.valueOf(ordinal);
    }
}
