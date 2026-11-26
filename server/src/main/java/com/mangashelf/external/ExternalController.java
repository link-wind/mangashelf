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
    private final CatalogRankingService ranking;

    @org.springframework.beans.factory.annotation.Autowired
    public ExternalController(ExternalService externals, ExternalCatalogService catalog,
                              BangumiService bangumi, CoverCacheService coverCache,
                              CatalogRankingService ranking) {
        this.externals = externals;
        this.catalog = catalog;
        this.bangumi = bangumi;
        this.coverCache = coverCache;
        this.ranking = ranking;
    }

    public ExternalController(ExternalService externals, ExternalCatalogService catalog,
                              BangumiService bangumi, CoverCacheService coverCache) {
        this(externals, catalog, bangumi, coverCache, null);
    }

    ExternalController(ExternalService externals, ExternalCatalogService catalog, BangumiService bangumi) {
        this(externals, catalog, bangumi, null, null);
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
    public ExternalService.ResolveStatus resolve(@OptionalAuthUser CurrentUser user,
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
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) Boolean season,
            @RequestParam(required = false) String year,
            @RequestParam(required = false) Integer page) {
        return catalog.listItems(cursor, limit, q, sourceId, categoryId, status, sort, genre, season, year, region, page);
    }

    @GetMapping("/catalog-items/genres")
    public List<ExternalCatalogRepository.GenreStat> catalogGenres(@OptionalAuthUser CurrentUser user) {
        return catalog.listGenres();
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

    /** 按需补齐 Bangumi v0 条目详情元数据（tags / air_date / air_time / studio / media_type / summary）。 */
    @PostMapping("/admin/bangumi-enrich")
    public BangumiService.EnrichStats enrichBangumi(@AuthUser CurrentUser user,
                                                    @RequestParam(defaultValue = "80") int limit,
                                                    @RequestParam(defaultValue = "false") boolean force) {
        if (!user.isAdmin()) {
            throw new com.mangashelf.config.ApiException(403, "FORBIDDEN", "仅管理员可执行元数据补齐。");
        }
        return bangumi.enrichSubjectsMetadata(limit, force);
    }

    /** 管理员批量补齐高清横幅（AniList 1900×400）与热门未关联动画高清封面。 */
    @PostMapping("/admin/anime-hd-enrich")
    public BangumiService.HdEnrichStats enrichAnimeHdAssets(
            @AuthUser CurrentUser user,
            @RequestParam(defaultValue = "60") int banners,
            @RequestParam(defaultValue = "60") int catalog) {
        if (!user.isAdmin()) {
            throw new com.mangashelf.config.ApiException(403, "FORBIDDEN", "仅管理员可执行高清横幅与封面补齐。");
        }
        return bangumi.batchEnrichHdAssets(banners, catalog);
    }

    /** 管理员：立即重算全站番剧与漫画的跨源地区传播、贝叶斯评分与综合推荐分。 */
    @PostMapping("/admin/catalog-ranking/recompute")
    public CatalogRankingService.RecomputeStats recomputeRanking(@AuthUser CurrentUser user) {
        if (!user.isAdmin()) {
            throw new com.mangashelf.config.ApiException(403, "FORBIDDEN", "仅管理员可执行推荐分重算。");
        }
        return ranking.recomputeAll();
    }

    /** 管理员：批量补齐番剧 Bangumi + AniList 真实评分与地区（支持断点续跑）。 */
    @PostMapping("/admin/catalog-ranking/enrich-anime")
    public CatalogRankingService.AnimeEnrichStats enrichAnimeRanking(
            @AuthUser CurrentUser user,
            @RequestParam(defaultValue = "80") int limit) {
        if (!user.isAdmin()) {
            throw new com.mangashelf.config.ApiException(403, "FORBIDDEN", "仅管理员可执行番剧评分与地区补齐。");
        }
        return ranking.enrichAnimeRatingsAndRegions(limit);
    }

    /** 管理员：批量补齐漫画哔哩官方分地区/人气 + Bangumi 书籍 + AniList Manga 真实评分与地区。 */
    @PostMapping("/admin/catalog-ranking/enrich-manga")
    public CatalogRankingService.MangaEnrichStats enrichMangaRanking(
            @AuthUser CurrentUser user,
            @RequestParam(defaultValue = "80") int limit,
            @RequestParam(defaultValue = "true") boolean bilibiliAreas,
            @RequestParam(defaultValue = "25") int bilibiliPages) {
        if (!user.isAdmin()) {
            throw new com.mangashelf.config.ApiException(403, "FORBIDDEN", "仅管理员可执行漫画地区与评分补齐。");
        }
        return ranking.enrichMangaRatingsAndRegions(limit, bilibiliAreas, bilibiliPages);
    }

    /** 高清宽幅横幅代理：/bangumi-banners/{subjectId}（仅读库内白名单 banner_url，防 SSRF），持久化缓存 + ETag/304。 */
    @GetMapping("/bangumi-banners/{subjectId}")
    public ResponseEntity<byte[]> bangumiBanner(
            @OptionalAuthUser CurrentUser user,
            @PathVariable long subjectId,
            @org.springframework.web.bind.annotation.RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch)
            throws Exception {
        BangumiService.Cover banner = bangumi.fetchBanner(subjectId);
        if (banner == null) {
            return ResponseEntity.notFound().build();
        }
        return serveCover(banner.bytes(), banner.contentType(), ifNoneMatch);
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

    /** 新番时刻表（支持按季度筛选），公开可读。 */
    @GetMapping("/calendar")
    public List<BangumiService.CalendarEntry> calendar(@OptionalAuthUser CurrentUser user,
                                                       @RequestParam(required = false) String season) {
        return bangumi.calendar(season);
    }

    /** 可选季度列表，公开可读。 */
    @GetMapping("/calendar/seasons")
    public List<BangumiService.SeasonOption> calendarSeasons() {
        return bangumi.calendarSeasons();
    }

    /** 追番日历 .ics 订阅（RFC 5545）：支持全本季或登录态 onlyMine=true（仅本人收藏的本季追番）。 */
    @GetMapping(value = "/calendar/ics", produces = "text/calendar; charset=utf-8")
    public ResponseEntity<String> calendarIcs(@OptionalAuthUser CurrentUser user,
                                              @RequestParam(defaultValue = "false") boolean onlyMine,
                                              @RequestParam(defaultValue = "false") boolean coveredOnly) {
        List<BangumiService.CalendarEntry> entries = bangumi.calendar();
        String calName = "GemiNeko 新番时刻表";
        if (onlyMine) {
            if (user == null || user.isGuest()) {
                throw new com.mangashelf.config.ApiException(401, "UNAUTHENTICATED", "导出个人追番日历需要登录或携带 API Token。");
            }
            java.util.Set<String> favIds = catalog.listFavoriteCatalogIds(user.id());
            entries = entries.stream()
                    .filter(e -> e.catalogItemId() != null && favIds.contains(e.catalogItemId()))
                    .toList();
            calName = "GemiNeko 我的追番表";
        } else if (coveredOnly) {
            entries = entries.stream().filter(BangumiService.CalendarEntry::covered).toList();
            calName = "GemiNeko 可播新番表";
        }
        String ics = BangumiService.buildCalendarIcs(entries, calName);
        return ResponseEntity.ok()
                .header("Content-Type", "text/calendar; charset=utf-8")
                .header("Content-Disposition", "inline; filename=\"gemineko-schedule.ics\"")
                .header("Cache-Control", onlyMine ? "private, max-age=300" : "public, max-age=1800")
                .body(ics);
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
