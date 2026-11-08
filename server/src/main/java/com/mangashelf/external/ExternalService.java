package com.mangashelf.external;

import com.mangashelf.catalog.CatalogService;
import com.mangashelf.config.ApiException;
import com.mangashelf.identity.CurrentUser;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/** 外部集业务（DESIGN 17.2 模式：来源 ID 回链；所有者显式绑定）。 */
@Service
public class ExternalService {

    private final ExternalRepository externals;
    private final CatalogService catalog;
    private final RuleEngine ruleEngine;
    private final com.mangashelf.ingestion.JobCreationService jobCreation;

    public ExternalService(ExternalRepository externals, CatalogService catalog,
                           RuleEngine ruleEngine,
                           com.mangashelf.ingestion.JobCreationService jobCreation) {
        this.externals = externals;
        this.catalog = catalog;
        this.ruleEngine = ruleEngine;
        this.jobCreation = jobCreation;
    }

    // ── 规则源 ──

    public ExternalSource createSource(CurrentUser user, String name, String baseUrl,
                                       JsonNode rules, boolean enabled) {
        if (!user.isAdmin()) {
            throw new ApiException(403, "FORBIDDEN", "只有管理员可以管理规则源。");
        }
        UUID id = externals.insertSource(name, baseUrl, rules.toString(), enabled);
        return externals.findSource(id).orElseThrow();
    }

    public List<ExternalSource> listSources(CurrentUser user, boolean enabledOnly) {
        if (!enabledOnly && !user.isAdmin()) {
            throw new ApiException(403, "FORBIDDEN", "只有管理员可以查看全部规则源。");
        }
        return externals.listSources(enabledOnly);
    }

    public void setSourceEnabled(CurrentUser user, UUID sourceId, boolean enabled) {
        requireAdmin(user);
        if (!externals.setSourceEnabled(sourceId, enabled)) {
            throw new ApiException(404, "SOURCE_NOT_FOUND", "规则源不存在。");
        }
    }

    public boolean deleteSource(CurrentUser user, UUID sourceId) {
        requireAdmin(user);
        boolean deleted = externals.deleteSource(sourceId);
        if (!deleted) {
            throw new ApiException(404, "SOURCE_NOT_FOUND", "规则源不存在。");
        }
        return deleted;
    }

    private void requireAdmin(CurrentUser user) {
        if (!user.isAdmin()) {
            throw new ApiException(403, "FORBIDDEN", "只有管理员可以管理规则源。");
        }
    }

    // ── 搜索 ──

    public record ExternalSearchResult(String name, String url) {
    }

    public List<ExternalSearchResult> searchExternal(CurrentUser user, UUID workId,
                                                      UUID sourceId, String keyword) {
        catalog.requireOwnedWork(user, workId);
        ExternalSource source = externals.findSource(sourceId).orElseThrow(
                () -> new ApiException(404, "SOURCE_NOT_FOUND", "规则源不存在。"));
        if (!source.enabled()) {
            throw new ApiException(409, "SOURCE_DISABLED", "规则源已停用。");
        }
        List<RuleEngine.SearchResultItem> results = ruleEngine.search(
                source.rules(), source.baseUrl(), keyword);
        return results.stream()
                .map(r -> new ExternalSearchResult(r.name(), r.url()))
                .toList();
    }

    // ── 分集解析 ──

    public record ExternalEpisodeResult(String title, String url) {
    }

    public List<ExternalEpisodeResult> parseEpisodes(CurrentUser user, UUID workId,
                                                      UUID sourceId, String episodePageUrl) {
        catalog.requireOwnedWork(user, workId);
        ExternalSource source = externals.findSource(sourceId).orElseThrow(
                () -> new ApiException(404, "SOURCE_NOT_FOUND", "规则源不存在。"));
        List<RuleEngine.EpisodeItem> episodes;
        try {
            episodes = ruleEngine.parseEpisodes(source.rules(), source.baseUrl(), episodePageUrl);
        } catch (Exception e) {
            throw new ApiException(502, "EXTERNAL_PARSE_FAILED", "外部站点解析失败：" + e.getMessage());
        }
        return episodes.stream()
                .map(e -> new ExternalEpisodeResult(e.title(), e.url()))
                .toList();
    }

    // ── 绑定 ──

    public record BindEpisodeRequest(String title, String url, int ordinal) {
    }

    public record BoundEpisode(UUID id, UUID sourceId, String sourceName, String title,
                               String episodeUrl, int ordinal) {
    }

    public List<BoundEpisode> bindEpisodes(CurrentUser user, UUID workId, UUID sourceId,
                                           List<BindEpisodeRequest> episodes) {
        catalog.requireOwnedWork(user, workId);
        ExternalSource source = externals.findSource(sourceId).orElseThrow(
                () -> new ApiException(404, "SOURCE_NOT_FOUND", "规则源不存在。"));
        for (var e : episodes) {
            if (e.title() == null || e.title().isBlank() || e.url() == null || e.url().isBlank()) {
                throw new ApiException(400, "EPISODE_INVALID", "外部集标题和地址不能为空。");
            }
        }
        for (var e : episodes) {
            externals.insertEpisode(UUID.randomUUID(), workId, sourceId,
                    e.title(), e.url(), e.ordinal());
        }
        return listEpisodes(user, workId);
    }

    public List<BoundEpisode> listEpisodes(CurrentUser user, UUID workId) {
        catalog.requireVisibleWork(user, workId);
        return externals.listEpisodes(workId).stream()
                .map(e -> {
                    String sourceName = externals.findSource(e.sourceId())
                            .map(ExternalSource::name).orElse("未知来源");
                    return new BoundEpisode(e.id(), e.sourceId(), sourceName,
                            e.title(), e.episodeUrl(), e.ordinal());
                })
                .toList();
    }

    public boolean unbindEpisode(CurrentUser user, UUID episodeId) {
        return externals.deleteEpisode(episodeId, user.id());
    }

    // ── 播放解析（M7 design.md D1/D4）──

    /** 解析结果新鲜窗口：源站 token 实测分钟级，4 分钟为保守值。 */
    private static final java.time.Duration RESOLVE_FRESH = java.time.Duration.ofMinutes(4);
    /** 幂等窗口（秒）：窗口内重复点击共享同一任务，过期允许重新解析。 */
    private static final long RESOLVE_DEDUP_WINDOW_SECONDS = 30;

    /** 解析状态（POST/GET 共用读取模型；playUrl 仅在新鲜窗口内返回）。 */
    public record ResolveStatus(UUID jobId, String jobState, String playUrl, String playKind,
                                java.time.Instant resolvedAt, String errorCode, String errorMessage) {
    }

    public ResolveStatus resolvePlayback(CurrentUser user, UUID episodeId) {
        ExternalEpisode episode = requireVisibleEpisode(user, episodeId);
        var latest = externals.findLatestResolveJob(episodeId);
        boolean active = latest.isPresent()
                && (com.mangashelf.ingestion.Job.STATUS_QUEUED.equals(latest.get().status())
                    || com.mangashelf.ingestion.Job.STATUS_RUNNING.equals(latest.get().status()));
        if (!active) {
            long window = java.time.Instant.now().getEpochSecond() / RESOLVE_DEDUP_WINDOW_SECONDS;
            jobCreation.create(com.mangashelf.ingestion.Job.KIND_EXTERNAL_RESOLVE, user.id(),
                    null, null, null, null, episodeId,
                    "ext.resolve:" + episodeId + ":" + window);
        }
        return statusOf(episode);
    }

    public ResolveStatus resolveStatus(CurrentUser user, UUID episodeId) {
        ExternalEpisode episode = requireVisibleEpisode(user, episodeId);
        return statusOf(episode);
    }

    private ExternalEpisode requireVisibleEpisode(CurrentUser user, UUID episodeId) {
        ExternalEpisode episode = externals.findEpisode(episodeId).orElseThrow(
                () -> new ApiException(404, "EPISODE_NOT_FOUND", "外部集不存在。"));
        catalog.requireVisibleWork(user, episode.workId());
        return episode;
    }

    private ResolveStatus statusOf(ExternalEpisode episode) {
        var job = externals.findLatestResolveJob(episode.id()).orElse(null);
        boolean fresh = episode.resolvedAt() != null
                && episode.resolvedAt().isAfter(java.time.Instant.now().minus(RESOLVE_FRESH));
        return new ResolveStatus(
                job != null ? job.id() : null,
                job != null ? job.status() : "NONE",
                fresh ? episode.resolvedUrl() : null,
                fresh ? episode.resolvedKind() : null,
                episode.resolvedAt(),
                job != null ? job.errorCode() : null,
                job != null ? job.errorMessage() : null);
    }
}
