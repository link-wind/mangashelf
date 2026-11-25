package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import com.mangashelf.identity.OptionalAuthUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 漫画目录端点（manga-catalog-sync）：索引页读库，题材从库内 tags 聚合。 */
@RestController
@RequestMapping("/api/v1/manga-catalog")
public class MangaCatalogController {

    private final MangaCatalogRepository catalog;

    public MangaCatalogController(MangaCatalogRepository catalog) {
        this.catalog = catalog;
    }

    public record CatalogPage(List<CatalogItemView> items, String nextCursor, long total) {}

    public record CatalogItemView(String source, String sourceItemId, String name, String altTitle,
                                  String coverUrl, String remarks, List<String> tags,
                                  Integer episodeCount) {}

    @GetMapping
    public CatalogPage page(@OptionalAuthUser CurrentUser user,
                            @RequestParam String source,
                            @RequestParam(defaultValue = "24") int limit,
                            @RequestParam(required = false) String genre,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String q,
                            @RequestParam(required = false) String cursor) {
        Instant before = Instant.parse("9999-12-31T23:59:59Z");
        UUID beforeId = new UUID(Long.MAX_VALUE, Long.MAX_VALUE);
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] parts = cursor.split("\\|", 2);
                before = Instant.parse(parts[0]);
                beforeId = UUID.fromString(parts[1]);
            } catch (Exception e) {
                throw new ApiException(400, "INVALID_ARGUMENT", "游标不合法。");
            }
        }
        List<MangaCatalogRepository.MangaCatalogItem> items =
                catalog.page(source, genre, status, q, before, beforeId,
                        Math.min(Math.max(limit, 1), 60));
        String next = items.size() == Math.min(Math.max(limit, 1), 60)
                ? encode(items.getLast().syncedAt(), items.getLast().id())
                : null;
        return new CatalogPage(items.stream().map(MangaCatalogController::view).toList(), next,
                catalog.count(source, genre, status, q));
    }

    @GetMapping("/genres")
    public List<MangaCatalogRepository.GenreTag> genres(@OptionalAuthUser CurrentUser user,
                                                        @RequestParam String source) {
        return catalog.genres(source);
    }

    private static CatalogItemView view(MangaCatalogRepository.MangaCatalogItem item) {
        return new CatalogItemView(item.source(), item.sourceItemId(), item.name(),
                item.altTitle(), item.coverUrl().isBlank() ? null : item.coverUrl(),
                item.remarks().isBlank() ? null : item.remarks(), item.tags(), item.episodeCount());
    }

    private static String encodeCursor(Instant t, UUID id) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((t.toString() + "|" + id).getBytes());
    }

    private static String encode(Instant t, UUID id) {
        return encodeCursor(t, id);
    }
}
