package com.mangashelf.external;

import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
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

    public ExternalController(ExternalService externals, ExternalCatalogService catalog,
                              BangumiService bangumi) {
        this.externals = externals;
        this.catalog = catalog;
        this.bangumi = bangumi;
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
    public List<ExternalSource> listSources(@AuthUser CurrentUser user,
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
    public ExternalService.ResolveStatus resolveStatus(@AuthUser CurrentUser user,
                                                       @PathVariable UUID id) {
        return externals.resolveStatus(user, id);
    }

    // ── 资源目录（M8）──

    public record SyncRequest(@NotNull UUID sourceId, List<Integer> categories, String mode) {}

    @PostMapping("/admin/catalog-sync")
    public ExternalCatalogService.SyncStarted triggerSync(@AuthUser CurrentUser user,
                                                          @Valid @RequestBody SyncRequest request) {
        return catalog.triggerSync(user, request.sourceId(), request.categories(), request.mode());
    }

    @GetMapping("/admin/catalog-sync")
    public Job syncStatus(@AuthUser CurrentUser user, @RequestParam UUID sourceId) {
        return catalog.syncStatus(user, sourceId);
    }

    @GetMapping("/catalog-items")
    public ExternalCatalogService.CatalogItemPage catalogItems(
            @AuthUser CurrentUser user,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "24") int limit,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID sourceId,
            @RequestParam(required = false) Integer categoryId) {
        return catalog.listItems(cursor, limit, q, sourceId, categoryId);
    }

    // ── 弹幕（M13）──

    @GetMapping("/danmaku")
    public List<DanmakuRepository.Danmaku> listDanmaku(@AuthUser CurrentUser user,
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

    // ── 观看进度与历史（M10）──

    public record ProgressRequest(@NotNull UUID workId, int ordinal, UUID episodeId,
                                  long positionMs, Long durationMs, Integer expectedVersion) {}

    @PutMapping("/external-progress")
    public ExternalRepository.WatchSnapshot saveProgress(@AuthUser CurrentUser user,
                                                         @Valid @RequestBody ProgressRequest request) {
        return externals.saveProgress(user, request.workId(), request.ordinal(), request.episodeId(),
                request.positionMs(), request.durationMs(), request.expectedVersion());
    }

    /** 单条进度：无记录返回版本 0 快照，播放器据此携带 expectedVersion。 */
    @GetMapping("/me/external-progress")
    public ExternalRepository.WatchSnapshot progress(@AuthUser CurrentUser user,
                                                     @RequestParam UUID workId,
                                                     @RequestParam int ordinal) {
        return externals.progress(user, workId, ordinal);
    }

    @GetMapping("/me/continue-watching")
    public List<ExternalRepository.ProgressRow> continueWatching(@AuthUser CurrentUser user,
                                                                 @RequestParam(defaultValue = "6") int limit) {
        return externals.recentProgress(user, limit);
    }

    @GetMapping("/me/watch-history")
    public List<ExternalRepository.ProgressRow> watchHistory(@AuthUser CurrentUser user,
                                                             @RequestParam(defaultValue = "50") int limit) {
        return externals.recentProgress(user, limit);
    }

    // ── Bangumi 元数据（M9）──

    @PostMapping("/admin/bangumi-sync")
    public Job triggerBangumiSync(@AuthUser CurrentUser user) {
        return catalog.triggerBangumiSync(user);
    }

    @GetMapping("/admin/bangumi-sync")
    public Job bangumiSyncStatus(@AuthUser CurrentUser user) {
        return catalog.bangumiSyncStatus(user);
    }

    /** Bangumi 封面代理：/bangumi-covers/{subjectId}（不接收任意 URL）。 */
    @GetMapping("/bangumi-covers/{subjectId}")
    public ResponseEntity<byte[]> bangumiCover(@AuthUser CurrentUser user,
                                               @PathVariable long subjectId,
                                               @RequestParam(defaultValue = "medium") String size)
            throws Exception {
        BangumiService.Cover cover = bangumi.fetchCover(subjectId, size);
        if (cover == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header("Content-Type", cover.contentType())
                .header("Cache-Control", "public, max-age=604800")
                .body(cover.bytes());
    }

    /** 本季新番日历（Bangumi 元数据 + 资源覆盖标记），登录可见。 */
    @GetMapping("/calendar")
    public List<BangumiService.CalendarEntry> calendar(@AuthUser CurrentUser user) {
        return bangumi.calendar();
    }

    @GetMapping("/catalog-items/{id}/episodes")
    public ExternalCatalogService.BrowseEpisodes browseEpisodes(@AuthUser CurrentUser user,
                                                                @PathVariable UUID id) {
        return catalog.browseEpisodes(user, id);
    }

    @GetMapping("/catalog-items/{id}/play-target")
    public Map<String, Object> browsePlayTarget(@AuthUser CurrentUser user, @PathVariable UUID id) {
        UUID episodeId = catalog.browsePlayTarget(user, id);
        return episodeId == null ? Map.of() : Map.of("episodeId", episodeId);
    }

    @GetMapping("/catalog-items/{id}")
    public ExternalCatalogService.CatalogDetail catalogItemDetail(@AuthUser CurrentUser user,
                                                                  @PathVariable UUID id) {
        return catalog.itemDetail(user, id);
    }

    @PostMapping("/catalog-items/{id}/materialize")
    public ExternalCatalogService.Materialized materialize(@AuthUser CurrentUser user,
                                                           @PathVariable UUID id) {
        return catalog.materialize(user, id);
    }
}
