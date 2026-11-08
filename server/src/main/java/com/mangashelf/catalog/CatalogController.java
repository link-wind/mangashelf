package com.mangashelf.catalog;

import com.mangashelf.config.ApiException;
import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 目录端点（DESIGN 16.1 目录 / 版本 / 页序 / 发布 / 阅读）。 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    public record CreateWorkRequest(@NotBlank @Size(max = 200) String title,
                                    @Size(max = 2000) String description) {
    }

    @PostMapping("/works")
    public CatalogService.WorkView create(@AuthUser CurrentUser user,
                                          @Valid @RequestBody CreateWorkRequest request) {
        Work work = catalog.createWork(user, request.title(),
                request.description() == null ? "" : request.description());
        return new CatalogService.WorkView(work, List.of(), true);
    }

    @GetMapping("/works")
    public CatalogService.WorkListPage list(@AuthUser CurrentUser user,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(defaultValue = "20") int limit,
                                            @RequestParam(required = false) String q) {
        return catalog.listWorks(user, cursor, limit, q);
    }

    @GetMapping("/works/{id}")
    public CatalogService.WorkView detail(@AuthUser CurrentUser user, @PathVariable UUID id) {
        return catalog.workView(user, id);
    }

    @PatchMapping("/works/{id}")
    public CatalogService.WorkView patch(@AuthUser CurrentUser user, @PathVariable UUID id,
                                         @Valid @RequestBody CatalogService.PatchWorkRequest request) {
        return new CatalogService.WorkView(catalog.patchWork(user, id, request), List.of(), true);
    }

    @PostMapping("/works/{id}/publish")
    public CatalogService.WorkView publish(@AuthUser CurrentUser user, @PathVariable UUID id) {
        return new CatalogService.WorkView(catalog.publishWork(user, id), List.of(), true);
    }

    @PostMapping("/works/{id}/withdraw")
    public CatalogService.WorkView withdraw(@AuthUser CurrentUser user, @PathVariable UUID id) {
        return new CatalogService.WorkView(catalog.withdrawWork(user, id), List.of(), true);
    }

    public record CreateChapterRequest(@Size(max = 200) String title) {
    }

    @PostMapping("/works/{id}/chapters")
    public Chapter createChapter(@AuthUser CurrentUser user, @PathVariable UUID id,
                                 @Valid @RequestBody CreateChapterRequest request) {
        return catalog.createChapter(user, id, request.title() == null ? "" : request.title());
    }

    @GetMapping("/chapters/{id}")
    public CatalogService.ChapterView chapter(@AuthUser CurrentUser user, @PathVariable UUID id) {
        return catalog.chapterView(user, id);
    }

    @PostMapping("/chapters/{id}/revisions")
    public CatalogService.RevisionCreated createRevision(@AuthUser CurrentUser user,
                                                         @PathVariable UUID id,
                                                         @Valid @RequestBody CatalogService.CreateRevisionRequest request) {
        return catalog.createRevision(user, id, request.uploadId());
    }

    @GetMapping("/chapter-revisions/{id}")
    public ChapterRevision revision(@AuthUser CurrentUser user, @PathVariable UUID id) {
        return catalog.requireOwnedRevision(user, id);
    }

    @PatchMapping("/chapter-revisions/{id}/page-order")
    public ChapterRevision pageOrder(@AuthUser CurrentUser user, @PathVariable UUID id,
                                     @Valid @RequestBody CatalogService.PageOrderRequest request) {
        return catalog.confirmPageOrder(user, id, request);
    }

    @PostMapping("/chapter-revisions/{id}/publish")
    public ChapterRevision publishRevision(@AuthUser CurrentUser user, @PathVariable UUID id) {
        return catalog.publishRevision(user, id);
    }

    @GetMapping("/chapter-revisions/{id}/pages")
    public CatalogService.PagesPage pages(@AuthUser CurrentUser user, @PathVariable UUID id,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(defaultValue = "20") int limit) {
        return catalog.pages(user, id, cursor, limit);
    }
}
