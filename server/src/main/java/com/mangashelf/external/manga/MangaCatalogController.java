package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import com.mangashelf.identity.OptionalAuthUser;
import com.mangashelf.ingestion.Job;
import com.mangashelf.ingestion.JobCreationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 漫画目录端点（manga-catalog-sync）：索引页读库，题材从库内 tags 聚合；管理员可按源触发同步。 */
@RestController
@RequestMapping("/api/v1/manga-catalog")
public class MangaCatalogController {

    private static final long SYNC_DEDUP_WINDOW_SECONDS = 30;

    private final MangaCatalogRepository catalog;
    private final MangaSourceRegistry registry;
    private final JobCreationService jobCreation;
    private final ObjectMapper objectMapper;

    @Autowired
    public MangaCatalogController(MangaCatalogRepository catalog, MangaSourceRegistry registry,
                                  JobCreationService jobCreation, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.registry = registry;
        this.jobCreation = jobCreation;
        this.objectMapper = objectMapper;
    }

    MangaCatalogController(MangaCatalogRepository catalog) {
        this(catalog, null, null, null);
    }

    public record CatalogPage(List<CatalogItemView> items, String nextCursor, long total,
                              int page, int limit, int totalPages) {}

    public record CatalogItemView(String source, String sourceItemId, String name, String altTitle,
                                  String coverUrl, String remarks, List<String> tags,
                                  Integer episodeCount, Double rating, String region) {}

    @GetMapping
    public CatalogPage page(@OptionalAuthUser CurrentUser user,
                            @RequestParam(required = false) String source,
                            @RequestParam(defaultValue = "24") int limit,
                            @RequestParam(required = false) String genre,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String region,
                            @RequestParam(required = false) String sort,
                            @RequestParam(required = false) String q,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(required = false) String cursor) {
        int effectiveLimit = Math.min(Math.max(limit, 1), 60);
        String effectiveSort = (sort == null || sort.isBlank()) ? "recommended" : sort.trim().toLowerCase();
        long total = catalog.count(source, genre, status, q, region);
        int totalPages = total == 0 ? 1 : (int) ((total + effectiveLimit - 1) / effectiveLimit);

        boolean useLegacyCursor = cursor != null && !cursor.isBlank() && page == null && "updated".equals(effectiveSort);
        if (!useLegacyCursor) {
            int currentPage = Math.max(page == null ? 1 : page, 1);
            int offset = (currentPage - 1) * effectiveLimit;
            List<MangaCatalogRepository.MangaCatalogItem> items =
                    catalog.page(source, genre, status, q, region, effectiveSort, null, null, offset, effectiveLimit);
            String next = currentPage < totalPages ? "page:" + (currentPage + 1) : null;
            return new CatalogPage(items.stream().map(MangaCatalogController::view).toList(),
                    next, total, currentPage, effectiveLimit, totalPages);
        }

        Instant before = Instant.parse("9999-12-31T23:59:59Z");
        UUID beforeId = new UUID(Long.MAX_VALUE, Long.MAX_VALUE);
        try {
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(cursor));
            String[] parts = decoded.split("\\|", 2);
            before = Instant.parse(parts[0]);
            beforeId = UUID.fromString(parts[1]);
        } catch (Exception e) {
            throw new ApiException(400, "INVALID_ARGUMENT", "游标不合法。");
        }
        List<MangaCatalogRepository.MangaCatalogItem> items =
                catalog.page(source, genre, status, q, region, effectiveSort, before, beforeId, null, effectiveLimit);
        String next = items.size() == effectiveLimit
                ? encode(items.getLast().syncedAt(), items.getLast().id())
                : null;
        return new CatalogPage(items.stream().map(MangaCatalogController::view).toList(),
                next, total, 1, effectiveLimit, totalPages);
    }

    @GetMapping("/genres")
    public List<MangaCatalogRepository.GenreTag> genres(@OptionalAuthUser CurrentUser user,
                                                        @RequestParam(required = false) String source) {
        return catalog.genres(source);
    }

    /** 管理员触发单源漫画目录同步（派发 manga.catalog.sync 异步任务，可选 maxPages 控制抓取深度）。 */
    @PostMapping("/admin/sync")
    public Map<String, Object> triggerSync(@AuthUser CurrentUser user,
                                           @RequestParam String sourceId,
                                           @RequestParam(required = false) Integer maxPages) {
        if (!user.isAdmin()) {
            throw new ApiException(403, "FORBIDDEN", "仅管理员可触发漫画目录同步。");
        }
        String sid = sourceId == null ? "" : sourceId.trim();
        boolean knownBuiltIn = "bilibili".equals(sid) || "copymanga".equals(sid) || "tencent".equals(sid);
        if (sid.isBlank() || (!knownBuiltIn && (registry == null || registry.find(sid).isEmpty()))) {
            throw new ApiException(404, "SOURCE_NOT_FOUND", "漫画源不存在：" + sid);
        }
        String paramsJson;
        try {
            var node = objectMapper.createObjectNode();
            node.put("sourceId", sid);
            if (maxPages != null && maxPages > 0) {
                node.put("maxPages", Math.min(maxPages, 269));
            }
            paramsJson = node.toString();
        } catch (Exception e) {
            throw new IllegalStateException("params 序列化失败", e);
        }
        long window = Instant.now().getEpochSecond() / SYNC_DEDUP_WINDOW_SECONDS;
        String dedupSuffix = maxPages == null ? "" : (":" + maxPages);
        UUID jobId = jobCreation.create(Job.KIND_MANGA_CATALOG_SYNC, user.id(),
                null, null, null, null, null, null,
                paramsJson, "manga.catalog.sync:" + sid + dedupSuffix + ":" + window);
        return Map.of("jobId", jobId, "sourceId", sid);
    }

    private static CatalogItemView view(MangaCatalogRepository.MangaCatalogItem item) {
        return new CatalogItemView(item.source(), item.sourceItemId(), item.name(),
                item.altTitle(), item.coverUrl().isBlank() ? null : item.coverUrl(),
                item.remarks().isBlank() ? null : item.remarks(), item.tags(), item.episodeCount(),
                item.rating(), item.region());
    }

    private static String encodeCursor(Instant t, UUID id) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((t.toString() + "|" + id).getBytes());
    }

    private static String encode(Instant t, UUID id) {
        return encodeCursor(t, id);
    }
}
