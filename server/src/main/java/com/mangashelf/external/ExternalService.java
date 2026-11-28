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
    private final DanmakuRepository danmaku;
    private final com.mangashelf.config.MangaShelfProperties props;

    public ExternalService(ExternalRepository externals, CatalogService catalog,
                           RuleEngine ruleEngine,
                           com.mangashelf.ingestion.JobCreationService jobCreation,
                           DanmakuRepository danmaku,
                           com.mangashelf.config.MangaShelfProperties props) {
        this.externals = externals;
        this.catalog = catalog;
        this.ruleEngine = ruleEngine;
        this.jobCreation = jobCreation;
        this.danmaku = danmaku;
        this.props = props;
    }

    // ── 规则源 ──

    public ExternalSource createSource(CurrentUser user, String name, String baseUrl,
                                       JsonNode rules, boolean enabled, String cookie) {
        if (!user.isAdmin()) {
            throw new ApiException(403, "FORBIDDEN", "只有管理员可以管理规则源。");
        }
        UUID id = externals.insertSource(name, baseUrl, rules.toString(), enabled,
                cookie == null ? "" : cookie.trim());
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
                               String episodeUrl, int ordinal, String lineName, int lineRank,
                               String episodeType) {
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
                            e.title(), e.episodeUrl(), e.ordinal(),
                            e.lineName(), e.lineRank(), e.episodeType());
                })
                .toList();
    }

    public boolean unbindEpisode(CurrentUser user, UUID episodeId) {
        return externals.deleteEpisode(episodeId, user.id());
    }

    /** 作品的外部元数据（M11 详情页）：封面、Bangumi 简介/评分、线路名。 */
    public record WorkExternalMeta(String coverUrl, String catalogTitle, Double rating,
                                   String summary, Integer airWeekday, List<String> lines) {}

    public WorkExternalMeta workExternalMeta(CurrentUser user, UUID workId) {
        catalog.requireVisibleWork(user, workId);
        return externals.workExternalMeta(workId);
    }

    // ── 弹幕（M13）──

    public List<DanmakuRepository.Danmaku> listDanmaku(CurrentUser user, UUID workId, int ordinal) {
        catalog.requireVisibleWork(user, workId);
        return danmaku.list(workId, ordinal);
    }

    public DanmakuRepository.Danmaku sendDanmaku(CurrentUser user, UUID workId, int ordinal,
                                                 UUID episodeId, long timeMs,
                                                 String content, String color) {
        catalog.requireVisibleWork(user, workId);
        String text = content == null ? "" : content.strip();
        if (text.isEmpty() || text.length() > 200) {
            throw new ApiException(400, "DANMAKU_INVALID", "弹幕内容需为 1–200 字。");
        }
        String c = color == null || color.isBlank() ? "#ffffff" : color;
        if (!c.matches("^#[0-9a-fA-F]{6}$")) {
            throw new ApiException(400, "DANMAKU_COLOR_INVALID", "弹幕颜色需为 #RRGGBB。");
        }
        return danmaku.insert(workId, ordinal, episodeId, user.id(), Math.max(0, timeMs), text, c);
    }

    /** 点进作品的播放目标（M19）：优先上次观看的集与线路，否则首集。 */
    public record PlayTarget(UUID episodeId, int ordinal, String lineName) {}

    public PlayTarget playTarget(CurrentUser user, UUID workId) {
        catalog.requireVisibleWork(user, workId);
        List<ExternalEpisode> eps = externals.listEpisodes(workId);
        if (eps.isEmpty()) return null;
        var progress = externals.latestProgressForWork(user.id(), workId);
        int ordinal = progress.map(ExternalRepository.ProgressHint::ordinal)
                .orElse(eps.get(0).ordinal());
        String line = progress.map(ExternalRepository.ProgressHint::lineName).orElse(null);
        ExternalEpisode chosen = eps.stream()
                .filter(e -> e.ordinal() == ordinal)
                .filter(e -> line == null || line.equals(e.lineName()))
                .findFirst()
                .orElseGet(() -> eps.stream().filter(e -> e.ordinal() == ordinal)
                        .findFirst().orElse(eps.get(0)));
        return new PlayTarget(chosen.id(), chosen.ordinal(), chosen.lineName());
    }

    // ── 观看进度（M10；按集统一路由目录/作品进度表）──

    public record EpisodeProgress(UUID episodeId, String targetKind, UUID targetId, int ordinal,
                                  long positionMs, Long durationMs, int version,
                                  java.time.Instant updatedAt) {}

    /** 单条进度；无记录时返回版本 0 快照（客户端可直接作为首次写入的 expectedVersion）。 */
    public EpisodeProgress progress(CurrentUser user, UUID episodeId) {
        ExternalRepository.EpisodeTarget target = requireTarget(user, episodeId);
        var stored = target.catalogItemId() != null
                ? externals.findCatalogProgress(user.id(), target.catalogItemId(), target.ordinal())
                : externals.findWorkProgress(user.id(), target.workId(), target.ordinal());
        return view(episodeId, target, stored.orElse(
                new ExternalRepository.StoredProgress(0L, null, 0, null)));
    }

    public EpisodeProgress saveProgress(CurrentUser user, UUID episodeId, long positionMs,
                                        Long durationMs, Integer expectedVersion) {
        if (expectedVersion == null) {
            throw new ApiException(400, "PROGRESS_INVALID", "缺少 expectedVersion（首次写入传 0）。");
        }
        ExternalRepository.EpisodeTarget target = requireTarget(user, episodeId);
        long position = Math.max(0, positionMs);
        var version = target.catalogItemId() != null
                ? externals.upsertCatalogProgress(user.id(), target.catalogItemId(), target.ordinal(),
                        episodeId, position, durationMs, expectedVersion)
                : externals.upsertWorkProgress(user.id(), target.workId(), target.ordinal(),
                        episodeId, position, durationMs, expectedVersion);
        if (version.isEmpty()) {
            throw new ApiException(409, "PROGRESS_CONFLICT", "进度已被其他设备更新，请刷新。",
                    java.util.Map.of("current", progress(user, episodeId)));
        }
        return progress(user, episodeId);
    }

    /** 目录条目优先：物化后剧集仍保留 catalog_item_id，进度键因此稳定。 */
    private ExternalRepository.EpisodeTarget requireTarget(CurrentUser user, UUID episodeId) {
        ExternalRepository.EpisodeTarget target = externals.episodeTarget(episodeId)
                .orElseThrow(() -> new ApiException(404, "EPISODE_NOT_FOUND", "剧集不存在。"));
        if (target.catalogItemId() == null) {
            if (target.workId() == null) {
                throw new ApiException(409, "EPISODE_UNBOUND", "剧集未绑定作品或目录条目。");
            }
            catalog.requireVisibleWork(user, target.workId());
        }
        return target;
    }

    private static EpisodeProgress view(UUID episodeId, ExternalRepository.EpisodeTarget target,
                                        ExternalRepository.StoredProgress stored) {
        boolean catalog = target.catalogItemId() != null;
        return new EpisodeProgress(episodeId, catalog ? "catalog" : "work",
                catalog ? target.catalogItemId() : target.workId(), target.ordinal(),
                stored.positionMs(), stored.durationMs(), stored.version(), stored.updatedAt());
    }

    // ── 播放解析（M7 design.md D1/D4 + 直链零等待/MacCMS 同步秒解）──

    /** 无明确 Expires 参数的短时效解析结果窗口（保守 4 分钟）。 */
    private static final java.time.Duration RESOLVE_FRESH = java.time.Duration.ofMinutes(4);
    /** 带明确 Expires 参数的签名直链最大缓存窗口（仍受 Expires 提前 120s 硬截断保护）。 */
    private static final java.time.Duration SIGNED_RESOLVE_MAX_FRESH = java.time.Duration.ofMinutes(60);
    /** 幂等窗口（秒）：窗口内重复点击共享同一任务，过期允许重新解析。 */
    private static final long RESOLVE_DEDUP_WINDOW_SECONDS = 30;

    /** 解析状态（POST/GET 共用读取模型；playUrl 仅在新鲜窗口内返回）。 */
    public record ResolveStatus(UUID jobId, String jobState, String playUrl, String playKind,
                                java.time.Instant resolvedAt, String errorCode, String errorMessage) {
    }

    private static final int RESOLVE_LIMIT_PER_MINUTE = 30;
    private final java.util.Map<UUID, java.util.ArrayDeque<Long>> resolveWindows = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<UUID, Object> fastResolveLocks = new java.util.concurrent.ConcurrentHashMap<>();

    /** 解析限流：防止公开部署时被当作免费解析接口（每用户 30 次/分钟，含预热）。 */
    private void checkResolveRate(CurrentUser user) {
        long now = System.currentTimeMillis();
        var window = resolveWindows.computeIfAbsent(user.id(), k -> new java.util.ArrayDeque<>());
        synchronized (window) {
            long cutoff = now - 60_000;
            while (!window.isEmpty() && window.peekFirst() < cutoff) window.pollFirst();
            if (window.size() >= RESOLVE_LIMIT_PER_MINUTE) {
                throw new ApiException(429, "RESOLVE_RATE_LIMITED", "解析过于频繁，请稍后再试。");
            }
            window.addLast(now);
        }
    }

    public ResolveStatus resolvePlayback(CurrentUser user, UUID episodeId) {
        checkResolveRate(user);
        ExternalEpisode episode = requireVisibleEpisode(user, episodeId);

        // 1. 剧集地址本身已是 .m3u8/.mp4 直链（如量子资源）：零队列、零 Python 毫秒级直出
        String directKind = FastPlaybackResolver.directVideoKind(episode.episodeUrl());
        if (directKind != null) {
            return new ResolveStatus(null, "SUCCEEDED", episode.episodeUrl(), directKind,
                    java.time.Instant.now(), null, null);
        }

        // 2. 已存在新鲜解析缓存，直接返回（避免重复打上游或建冗余任务）
        ResolveStatus cached = statusOf(episode);
        if (cached.playUrl() != null) {
            return cached;
        }

        // 3. 标准 MacCMS 播放页（如 7sefun 七色B线）在 API 层同步秒解，省去 Outbox/MQ/Python 开销
        Object lock = fastResolveLocks.computeIfAbsent(episodeId, k -> new Object());
        synchronized (lock) {
            ExternalEpisode refreshed = externals.findEpisode(episodeId).orElse(episode);
            ResolveStatus afterLock = statusOf(refreshed);
            if (afterLock.playUrl() != null) {
                return afterLock;
            }
            ExternalSource source = externals.findSource(refreshed.sourceId()).orElse(null);
            String referer = source != null ? source.baseUrl() : null;
            String cookie = source != null ? source.cookie() : null;
            var fast = FastPlaybackResolver.tryResolveMacCms(refreshed.episodeUrl(), referer, cookie);
            if (fast.isPresent()) {
                var media = fast.get();
                externals.markResolved(refreshed.id(), media.playUrl(), media.playKind());
                return new ResolveStatus(null, "SUCCEEDED", media.playUrl(), media.playKind(),
                        java.time.Instant.now(), null, null);
            }
        }

        // 4. 其余复杂页面回落至异步 Worker + Playwright 嗅探
        var latest = externals.findLatestResolveJob(episodeId);
        boolean active = latest.isPresent()
                && (com.mangashelf.ingestion.Job.STATUS_QUEUED.equals(latest.get().status())
                    || com.mangashelf.ingestion.Job.STATUS_RUNNING.equals(latest.get().status()));
        if (!active) {
            long window = java.time.Instant.now().getEpochSecond() / RESOLVE_DEDUP_WINDOW_SECONDS;
            // 访客触发解析时以系统所有者（admin）承载任务：外部集解析是公共操作，
            // 不涉及用户私有数据，且 jobs.owner_id 有外键到 users（全零访客 ID 无法建任务）。
            UUID jobOwner = user.isGuest() ? props.demoOwnerId() : user.id();
            jobCreation.create(com.mangashelf.ingestion.Job.KIND_EXTERNAL_RESOLVE, jobOwner,
                    null, null, null, null, episodeId, null, null,
                    "ext.resolve:" + episodeId + ":" + window);
        }
        return statusOf(episode);
    }

    public ResolveStatus resolveStatus(CurrentUser user, UUID episodeId) {
        ExternalEpisode episode = requireVisibleEpisode(user, episodeId);
        // 直链快路径：GET 时即刻返回，连 POST /resolve 都省去
        String directKind = FastPlaybackResolver.directVideoKind(episode.episodeUrl());
        if (directKind != null) {
            return new ResolveStatus(null, "SUCCEEDED", episode.episodeUrl(), directKind,
                    java.time.Instant.now(), null, null);
        }
        return statusOf(episode);
    }

    private ExternalEpisode requireVisibleEpisode(CurrentUser user, UUID episodeId) {
        ExternalEpisode episode = externals.findEpisode(episodeId).orElseThrow(
                () -> new ApiException(404, "EPISODE_NOT_FOUND", "外部集不存在。"));
        // M20：浏览态剧集挂目录条目，登录用户均可解析；入库剧集仍按作品可见性校验
        if (episode.workId() != null) {
            catalog.requireVisibleWork(user, episode.workId());
        } else if (episode.catalogItemId() == null) {
            throw new ApiException(404, "EPISODE_OWNER_MISSING", "剧集缺少归属。");
        }
        return episode;
    }

    private static final java.util.regex.Pattern URL_EXPIRES_PARAM =
            java.util.regex.Pattern.compile("(?:[?&])Expires=(\\d{9,12})(?:&|$)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final long SIGNED_URL_EXPIRY_MARGIN_SECONDS = 120L;

    private ResolveStatus statusOf(ExternalEpisode episode) {
        var job = externals.findLatestResolveJob(episode.id()).orElse(null);
        java.time.Instant now = java.time.Instant.now();
        boolean fresh = isResolvedFresh(episode.resolvedUrl(), episode.resolvedAt(), now);
        return new ResolveStatus(
                job != null ? job.id() : null,
                job != null ? job.status() : "NONE",
                fresh ? episode.resolvedUrl() : null,
                fresh ? episode.resolvedKind() : null,
                episode.resolvedAt(),
                job != null ? job.errorCode() : null,
                job != null ? job.errorMessage() : null);
    }

    /** 判定已落库的直链是否仍新鲜：带 Expires 的签名直链最长可复用 60 分钟（距过期需留 ≥120s 余量），其余保守 4 分钟。 */
    static boolean isResolvedFresh(String url, java.time.Instant resolvedAt, java.time.Instant now) {
        if (url == null || url.isBlank() || resolvedAt == null) return false;
        var m = URL_EXPIRES_PARAM.matcher(url);
        if (m.find()) {
            return resolvedAt.isAfter(now.minus(SIGNED_RESOLVE_MAX_FRESH)) && isUrlNotNearExpiry(url, now);
        }
        return resolvedAt.isAfter(now.minus(RESOLVE_FRESH));
    }

    /** 签名直链若带 Expires 参数，距过期不足 120 秒即视为失效，强制客户端触发重解析。 */
    static boolean isUrlNotNearExpiry(String url, java.time.Instant now) {
        if (url == null || url.isBlank()) return false;
        var m = URL_EXPIRES_PARAM.matcher(url);
        if (!m.find()) return true;
        try {
            long expiresEpoch = Long.parseLong(m.group(1));
            return expiresEpoch > now.getEpochSecond() + SIGNED_URL_EXPIRY_MARGIN_SECONDS;
        } catch (NumberFormatException ignored) {
            return true;
        }
    }
}
