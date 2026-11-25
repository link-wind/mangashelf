package com.mangashelf.external;

import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import com.mangashelf.identity.OptionalAuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mangashelf.ingestion.Job;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Validated
public class ExternalController {

    private final ExternalService externals;
    private final ExternalCatalogService catalog;
    private final BangumiService bangumi;
    private final CoverCacheService coverCache;

    @org.springframework.beans.factory.annotation.Autowired
    public ExternalController(ExternalService externals, ExternalCatalogService catalog,
                              BangumiService bangumi, CoverCacheService coverCache) {
        this.externals = externals;
        this.catalog = catalog;
        this.bangumi = bangumi;
        this.coverCache = coverCache;
    }

    ExternalController(ExternalService externals, ExternalCatalogService catalog, BangumiService bangumi) {
        this(externals, catalog, bangumi, null);
    }

    // ── 规则源管理（管理员）──

    public record CreateSourceRequest(
            @NotBlank String name,
            @NotBlank String baseUrl,
            @NotNull JsonNode rules,
            boolean enabled,
            String cookie) {
    }

    @PostMapping("/admin/external-sources")
    public ExternalSource createSource(@AuthUser CurrentUser user,
                                       @Valid @RequestBody CreateSourceRequest request) {
        return externals.createSource(user, request.name(), request.baseUrl(),
                request.rules(), request.enabled(), request.cookie());
    }

    @GetMapping("/external-sources")
    public List<ExternalSource> listSources(@OptionalAuthUser CurrentUser user,
                                            @RequestParam(defaultValue = "true") boolean enabledOnly) {
        return externals.listSources(user, enabledOnly);
    }

    @PostMapping("/admin/external-sources/{id}/toggle")
    public Map<String, Object> toggleSource(@AuthUser CurrentUser user, @PathVariable UUID id,
                                            @RequestParam boolean enabled) {
        externals.setSourceEnabled(user, id, enabled);
        return Map.of("id", id, "enabled", enabled);
    }

    @DeleteMapping("/admin/external-sources/{id}")
    public Map<String, Object> deleteSource(@AuthUser CurrentUser user, @PathVariable UUID id) {
        boolean ok = externals.deleteSource(user, id);
        return Map.of("id", id, "deleted", ok);
    }

    // ── 搜索与绑定（作品所有者）──

    public record SearchRequest(@NotNull UUID sourceId, @NotBlank String keyword) {
    }

    @PostMapping("/works/{workId}/external-search")
    public List<ExternalService.ExternalSearchResult> search(
            @AuthUser CurrentUser user, @PathVariable UUID workId,
            @Valid @RequestBody SearchRequest request) {
        return externals.searchExternal(user, workId, request.sourceId(), request.keyword());
    }

    public record EpisodeParseRequest(@NotNull UUID sourceId, @NotBlank String episodePageUrl) {
    }

    @PostMapping("/works/{workId}/external-episodes/parse")
    public List<ExternalService.ExternalEpisodeResult> parseEpisodes(
            @AuthUser CurrentUser user, @PathVariable UUID workId,
            @Valid @RequestBody EpisodeParseRequest request) {
        return externals.parseEpisodes(user, workId, request.sourceId(), request.episodePageUrl());
    }

    public record BindRequest(@NotNull UUID sourceId,
                              @NotEmpty List<ExternalService.BindEpisodeRequest> episodes) {
    }

    @PostMapping("/works/{workId}/external-episodes")
    public List<ExternalService.BoundEpisode> bind(@AuthUser CurrentUser user,
                                                    @PathVariable UUID workId,
                                                    @Valid @RequestBody BindRequest request) {
        return externals.bindEpisodes(user, workId, request.sourceId(), request.episodes());
    }

    @GetMapping("/works/{workId}/play-target")
    public ExternalService.PlayTarget playTarget(@AuthUser CurrentUser user,
                                                 @PathVariable UUID workId) {
        return externals.playTarget(user, workId);
    }

    @GetMapping("/works/{workId}/external-meta")
    public ExternalService.WorkExternalMeta externalMeta(@AuthUser CurrentUser user,
                                                         @PathVariable UUID workId) {
        return externals.workExternalMeta(user, workId);
    }

    @GetMapping("/works/{workId}/external-episodes")
    public List<ExternalService.BoundEpisode> list(@AuthUser CurrentUser user,
                                                    @PathVariable UUID workId) {
        return externals.listEpisodes(user, workId);
    }

    @DeleteMapping("/external-episodes/{id}")
    public Map<String, Object> unbind(@AuthUser CurrentUser user, @PathVariable UUID id) {
        boolean ok = externals.unbindEpisode(user, id);
        return Map.of("id", id, "removed", ok);
    }

    // ── 播放解析（M7）──

    @PostMapping("/external-episodes/{id}/resolve")
    public ExternalService.ResolveStatus resolve(@AuthUser CurrentUser user,
                                                 @PathVariable UUID id) {
        return externals.resolvePlayback(user, id);
    }

    @GetMapping("/external-episodes/{id}/resolve")
    public ExternalService.ResolveStatus resolveStatus(@OptionalAuthUser CurrentUser user,
                                                       @PathVariable UUID id) {
        return externals.resolveStatus(user, id);
    }

    // ── 资源目录（M8）──

    public record SyncRequest(@NotNull UUID sourceId, List<Integer> categories, String mode, Integer hours) {}

    @PostMapping("/admin/catalog-sync")
    public ExternalCatalogService.SyncStarted triggerSync(@AuthUser CurrentUser user,
                                                          @Valid @RequestBody SyncRequest request) {
        return catalog.triggerSync(user, request.sourceId(), request.categories(), request.mode(), request.hours());
    }

    @GetMapping("/admin/catalog-sync")
    public Job syncStatus(@AuthUser CurrentUser user, @RequestParam UUID sourceId) {
        return catalog.syncStatus(user, sourceId);
    }

    @GetMapping("/catalog-items")
    public ExternalCatalogService.CatalogItemPage catalogItems(
            @OptionalAuthUser CurrentUser user,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "24") int limit,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID sourceId,
            @RequestParam(required = false) Integer categoryId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) Boolean season,
            @RequestParam(required = false) Integer page) {
        return catalog.listItems(cursor, limit, q, sourceId, categoryId, status, sort, genre, season, page);
    }

    // ── 弹幕（M13）──

    @GetMapping("/danmaku")
    public List<DanmakuRepository.Danmaku> listDanmaku(@OptionalAuthUser CurrentUser user,
                                                       @RequestParam UUID workId,
                                                       @RequestParam int ordinal) {
        return externals.listDanmaku(user, workId, ordinal);
    }

    public record DanmakuRequest(@NotNull UUID workId, int ordinal, UUID episodeId,
                                 long timeMs, @NotBlank @Size(max = 200) String content,
                                 String color) {}

    @PostMapping("/danmaku")
    public DanmakuRepository.Danmaku sendDanmaku(@AuthUser CurrentUser user,
                                                 @Valid @RequestBody DanmakuRequest request) {
        return externals.sendDanmaku(user, request.workId(), request.ordinal(),
                request.episodeId(), request.timeMs(), request.content(), request.color());
    }

    // ── 观看进度与历史（M10；历史与续看已迁移到 /api/v1/me/history 与 /me/continue）──

    // ── Bangumi 元数据（M9）──

    @PostMapping("/admin/bangumi-sync")
    public Job triggerBangumiSync(@AuthUser CurrentUser user) {
        return catalog.triggerBangumiSync(user);
    }

    @GetMapping("/admin/bangumi-sync")
    public Job bangumiSyncStatus(@AuthUser CurrentUser user) {
        return catalog.bangumiSyncStatus(user);
    }

    /** Bangumi 封面代理：/bangumi-covers/{subjectId}（不接收任意 URL）；支持持久化缓存与 ETag/304。 */
    @GetMapping("/bangumi-covers/{subjectId}")
    public ResponseEntity<byte[]> bangumiCover(
            @OptionalAuthUser CurrentUser user,
            @PathVariable long subjectId,
            @RequestParam(defaultValue = "common") String size,
            @org.springframework.web.bind.annotation.RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch)
            throws Exception {
        BangumiService.Cover cover = bangumi.fetchCover(subjectId, size);
        if (cover == null) {
            return ResponseEntity.notFound().build();
        }
        return serveCover(cover.bytes(), cover.contentType(), ifNoneMatch);
    }

    /** 目录条目封面代理：/catalog-covers/{itemId}（仅查库内 URL，防 SSRF），持久化缓存 + ETag/304。 */
    @GetMapping("/catalog-covers/{itemId}")
    public ResponseEntity<byte[]> catalogCover(
            @OptionalAuthUser CurrentUser user,
            @PathVariable UUID itemId,
            @org.springframework.web.bind.annotation.RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        if (coverCache == null) {
            return ResponseEntity.notFound().build();
        }
        CoverCacheService.CoverData cover = coverCache.fetchCatalogCover(itemId);
        if (cover == null) {
            return ResponseEntity.notFound().build();
        }
        return serveCover(cover.bytes(), cover.contentType(), ifNoneMatch);
    }

    private static ResponseEntity<byte[]> serveCover(byte[] bytes, String contentType, String ifNoneMatch) {
        String etag = computeEtag(bytes);
        if (etagMatches(ifNoneMatch, etag)) {
            return ResponseEntity.status(304)
                    .header("ETag", etag)
                    .header("Cache-Control", "public, max-age=604800")
                    .build();
        }
        return ResponseEntity.ok()
                .header("Content-Type", contentType)
                .header("ETag", etag)
                .header("Cache-Control", "public, max-age=604800")
                .body(bytes);
    }

    private static String computeEtag(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return "\"" + java.util.HexFormat.of().formatHex(digest, 0, 16) + "\"";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String trimmed = candidate.trim();
            if ("*".equals(trimmed) || etag.equals(trimmed) || trimmed.equals("W/" + etag)) {
                return true;
            }
        }
        return false;
    }

    /** 本季新番日历（Bangumi 元数据 + 资源覆盖标记），登录可见。 */
    @GetMapping("/calendar")
    public List<BangumiService.CalendarEntry> calendar(@OptionalAuthUser CurrentUser user) {
        return bangumi.calendar();
    }

    @GetMapping("/catalog-items/{id}/episodes")
    public ExternalCatalogService.BrowseEpisodes browseEpisodes(@OptionalAuthUser CurrentUser user,
                                                                @PathVariable UUID id) {
        return catalog.browseEpisodes(user, id);
    }

    @GetMapping("/catalog-items/{id}/play-target")
    public Map<String, Object> browsePlayTarget(@OptionalAuthUser CurrentUser user, @PathVariable UUID id) {
        UUID episodeId = catalog.browsePlayTarget(user, id);
        return episodeId == null ? Map.of() : Map.of("episodeId", episodeId);
    }

    @GetMapping("/catalog-items/{id}")
    public ExternalCatalogService.CatalogDetail catalogItemDetail(@OptionalAuthUser CurrentUser user,
                                                                  @PathVariable UUID id) {
        return catalog.itemDetail(user, id);
    }

    @PostMapping("/catalog-items/{id}/materialize")
    public ExternalCatalogService.Materialized materialize(@AuthUser CurrentUser user,
                                                           @PathVariable UUID id) {
        return catalog.materialize(user, id);
    }
}
