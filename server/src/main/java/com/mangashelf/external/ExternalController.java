package com.mangashelf.external;

import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Validated
public class ExternalController {

    private final ExternalService externals;

    public ExternalController(ExternalService externals) {
        this.externals = externals;
    }

    // ── 规则源管理（管理员）──

    public record CreateSourceRequest(
            @NotBlank String name,
            @NotBlank String baseUrl,
            @NotNull JsonNode rules,
            boolean enabled) {
    }

    @PostMapping("/admin/external-sources")
    public ExternalSource createSource(@AuthUser CurrentUser user,
                                       @Valid @RequestBody CreateSourceRequest request) {
        return externals.createSource(user, request.name(), request.baseUrl(),
                request.rules(), request.enabled());
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
}
