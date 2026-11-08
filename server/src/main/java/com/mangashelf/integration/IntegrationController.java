package com.mangashelf.integration;

import com.mangashelf.animation.AnimationRepository;
import com.mangashelf.animation.AnimationService;
import com.mangashelf.catalog.CatalogRepository;
import com.mangashelf.catalog.CatalogService;
import com.mangashelf.catalog.ChapterRevision;
import com.mangashelf.catalog.Page;
import com.mangashelf.config.ApiException;
import com.mangashelf.config.MangaShelfProperties;
import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import com.mangashelf.identity.Scopes;
import com.mangashelf.identity.TokenScopes;
import com.mangashelf.storage.AssetRepository;
import com.mangashelf.storage.PresignService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * MangaWeaver 接入端点（DESIGN 17；协议 v1 见 docs/api/mangaweaver-integration.md）。
 * 全部走 Bearer Token + scope；错误结构与会话端点一致（DESIGN 16.2）。
 */
@RestController
@RequestMapping("/api/v1/integrations/mangaweaver")
@Validated
public class IntegrationController {

    public record ExportRequest(
            @JsonProperty("chapter_revision_id") UUID chapterRevisionId,
            @JsonProperty("page_ids") List<UUID> pageIds) {
    }

    public record ExportPage(@JsonProperty("page_id") UUID pageId, int ordinal,
                             @JsonProperty("asset_id") UUID assetId, String sha256,
                             @JsonProperty("download_url") String downloadUrl,
                             @JsonProperty("expires_at") String expiresAt) {
    }

    public record ExportManifest(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("platform_id") String platformId,
            @JsonProperty("work_id") String workId,
            @JsonProperty("chapter_id") String chapterId,
            @JsonProperty("chapter_revision_id") String chapterRevisionId,
            @JsonProperty("reading_direction") String readingDirection,
            List<ExportPage> pages) {
    }

    public record PublicationSourceRequest(
            @JsonProperty("chapter_revision_id") UUID chapterRevisionId,
            @JsonProperty("page_start") int pageStart,
            @JsonProperty("page_end") int pageEnd,
            int ordinal) {
    }

    public record PublicationRequest(
            @JsonProperty("schema_version") int schemaVersion,
            @NotEmpty String title,
            String description,
            @JsonProperty("requested_visibility") String requestedVisibility,
            @JsonProperty("animation_id") UUID animationId,
            @NotNull @JsonProperty("origin_run_ref") String originRunRef,
            @NotEmpty List<PublicationSourceRequest> sources) {
    }

    public record PublicationDraft(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("animation_id") UUID animationId,
            @JsonProperty("revision_id") UUID revisionId,
            @JsonProperty("revision_status") String revisionStatus,
            @JsonProperty("watch_url") String watchUrl) {
    }

    private final ExportService exports;
    private final PublicationService publications;

    public IntegrationController(ExportService exports, PublicationService publications) {
        this.exports = exports;
        this.publications = publications;
    }

    @PostMapping("/exports")
    public ExportManifest export(@AuthUser CurrentUser user,
                                 @Valid @RequestBody ExportRequest request) {
        TokenScopes.require(Scopes.MATERIALS_READ);
        return exports.buildManifest(user, request.chapterRevisionId(), request.pageIds());
    }

    @PostMapping("/publications")
    public PublicationDraft publish(@AuthUser CurrentUser user,
                                    @Valid @RequestBody PublicationRequest request) {
        TokenScopes.require(Scopes.ANIMATIONS_WRITE);
        return publications.createOrGet(user, request);
    }

    @GetMapping("/publications/{originRunRef}")
    public PublicationDraft status(@AuthUser CurrentUser user, @PathVariable String originRunRef) {
        TokenScopes.require(Scopes.ANIMATIONS_WRITE);
        return publications.status(user, originRunRef);
    }
}
