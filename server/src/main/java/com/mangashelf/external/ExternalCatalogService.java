package com.mangashelf.external;

import com.mangashelf.catalog.CatalogRepository;
import com.mangashelf.config.ApiException;
import com.mangashelf.config.HotspotCache;
import com.mangashelf.identity.CurrentUser;
import com.mangashelf.ingestion.Job;
import com.mangashelf.ingestion.JobCreationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 资源目录业务（M8 D3/D4）：同步触发、目录浏览、点播物化。 */
@Service
public class ExternalCatalogService {

    private static final long SYNC_DEDUP_WINDOW_SECONDS = 30;

    private final ExternalCatalogRepository catalogItems;
    private final ExternalRepository externals;
    private final CatalogRepository catalog;
    private final JobCreationService jobCreation;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final HotspotCache hotspotCache;
    private final BangumiService bangumi;
    private final RuleEngine ruleEngine;
    private final java.util.Map<UUID, java.util.ArrayDeque<Long>> extraLineRateWindows = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<UUID, Long> extraLineItemCooldown = new java.util.concurrent.ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ExternalCatalogService(ExternalCatalogRepository catalogItems, ExternalRepository externals,
                                  CatalogRepository catalog, JobCreationService jobCreation,
                                  TransactionTemplate tx, ObjectMapper objectMapper,
                                  org.springframework.jdbc.core.JdbcTemplate jdbc,
                                  HotspotCache hotspotCache,
                                  BangumiService bangumi,
                                  RuleEngine ruleEngine) {
        this.catalogItems = catalogItems;
        this.externals = externals;
        this.catalog = catalog;
        this.jobCreation = jobCreation;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.hotspotCache = hotspotCache;
        this.bangumi = bangumi;
        this.ruleEngine = ruleEngine;
    }

    ExternalCatalogService(ExternalCatalogRepository catalogItems, ExternalRepository externals,
                           CatalogRepository catalog, JobCreationService jobCreation,
                           TransactionTemplate tx, ObjectMapper objectMapper,
                           org.springframework.jdbc.core.JdbcTemplate jdbc,
                           HotspotCache hotspotCache) {
        this(catalogItems, externals, catalog, jobCreation, tx, objectMapper, jdbc, hotspotCache, null, null);
    }

    // ── 同步 ──

    public record SyncStarted(UUID jobId) {}

    public SyncStarted triggerSync(CurrentUser user, UUID sourceId, List<Integer> categories, String mode,
                                   Integer hours) {
        requireAdmin(user);
        if (externals.findSource(sourceId).isEmpty()) {
            throw new ApiException(404, "SOURCE_NOT_FOUND", "资源源不存在。");
        }
        var latest = catalogItems.findLatestSyncJob(sourceId);
        boolean active = latest.isPresent()
                && (Job.STATUS_QUEUED.equals(latest.get().status()) || Job.STATUS_RUNNING.equals(latest.get().status()));
        if (!active) {
            String effectiveMode = mode == null ? "full" : mode;
            int effectiveHours = hours == null ? 168 : Math.min(720, Math.max(1, hours));
            String params;
            try {
                var node = objectMapper.createObjectNode();
                node.put("mode", effectiveMode);
                if ("incremental".equals(effectiveMode)) {
                    node.put("hours", effectiveHours);
                }
                node.set("categories", objectMapper.valueToTree(categories == null ? List.of() : categories));
                params = node.toString();
            } catch (Exception e) {
                throw new IllegalStateException("params 序列化失败", e);
            }
            long window = Instant.now().getEpochSecond() / SYNC_DEDUP_WINDOW_SECONDS;
            UUID jobId = jobCreation.create(Job.KIND_CATALOG_SYNC, user.id(), null, null, null, null, null,
                    sourceId, params, "catalog.sync:" + sourceId + ":" + effectiveMode + ":" + effectiveHours + ":" + window);
            return new SyncStarted(jobId);
        }
        return new SyncStarted(latest.map(Job::id).orElse(null));
    }

    public Job syncStatus(CurrentUser user, UUID sourceId) {
        requireAdmin(user);
        return catalogItems.findLatestSyncJob(sourceId).orElse(null);
    }

    // ── Bangumi 元数据同步（M9）──

    public Job triggerBangumiSync(CurrentUser user) {
        requireAdmin(user);
        var latest = catalogItems.findLatestBangumiSyncJob();
        boolean active = latest.isPresent()
                && (Job.STATUS_QUEUED.equals(latest.get().status()) || Job.STATUS_RUNNING.equals(latest.get().status()));
        if (!active) {
            long window = Instant.now().getEpochSecond() / SYNC_DEDUP_WINDOW_SECONDS;
            jobCreation.create(Job.KIND_BANGUMI_SYNC, user.id(), null, null, null, null, null,
                    null, null, "bangumi.sync:" + window);
        }
        return catalogItems.findLatestBangumiSyncJob().orElse(null);
    }

    public Job bangumiSyncStatus(CurrentUser user) {
        requireAdmin(user);
        return catalogItems.findLatestBangumiSyncJob().orElse(null);
    }

    // ── 目录浏览 ──

    public record CatalogItemPage(List<ExternalCatalogItem> items, String nextCursor, long total,
                                  int page, int limit, int totalPages) {}

    public CatalogItemPage listItems(String cursor, int limit, String q, UUID sourceId,
                                     Integer categoryId, String status, String sort, String genre,
                                     Boolean season, Integer page) {
        return listItems(cursor, limit, q, sourceId, categoryId, status, sort, genre, season, null, null, page);
    }

    public CatalogItemPage listItems(String cursor, int limit, String q, UUID sourceId,
                                     Integer categoryId, String status, String sort, String genre,
                                     Boolean season, String year, Integer page) {
        return listItems(cursor, limit, q, sourceId, categoryId, status, sort, genre, season, year, null, page);
    }

    public CatalogItemPage listItems(String cursor, int limit, String q, UUID sourceId,
                                     Integer categoryId, String status, String sort, String genre,
                                     Boolean season, String year, String region, Integer page) {
        int effectiveLimit = Math.min(Math.max(limit, 1), 60);
        boolean hasQuery = q != null && !q.isBlank();
        String effectiveSort = (sort == null || sort.isBlank()) ? "recommended" : sort.trim().toLowerCase();
        boolean useLegacyCursor = cursor != null && !cursor.isBlank() && page == null && "updated".equals(effectiveSort);
        String countKey = "catalog-count:" + sourceId + ":" + categoryId + ":" + region + ":" + status + ":"
                + genre + ":" + season + ":" + year + ":" + (hasQuery ? q.trim() : "");

        int currentPage = Math.max(page == null ? 1 : page, 1);
        if (!hasQuery && !useLegacyCursor && currentPage == 1) {
            String pageKey = "catalog-page:" + effectiveSort + ":" + effectiveLimit + ":" + sourceId + ":"
                    + categoryId + ":" + region + ":" + status + ":" + genre + ":" + season + ":" + year;
            return hotspotCache.getOrLoad("catalog_page", pageKey, Duration.ofSeconds(30), () -> {
                List<ExternalCatalogItem> items = rewriteCovers(catalogItems.page(sourceId, categoryId, null, status,
                        effectiveSort, genre, season, year, region, null, null, 0, effectiveLimit));
                long total = hotspotCache.getOrLoad("catalog_count", countKey, Duration.ofSeconds(60),
                        () -> catalogItems.count(sourceId, categoryId, null, status, genre, season, year, region));
                int totalPages = total == 0 ? 1 : (int) ((total + effectiveLimit - 1) / effectiveLimit);
                String next = 1 < totalPages ? "page:2" : null;
                return new CatalogItemPage(List.copyOf(items), next, total, 1, effectiveLimit, totalPages);
            });
        }

        long total = hasQuery
                ? catalogItems.count(sourceId, categoryId, q, status, genre, season, year, region)
                : hotspotCache.getOrLoad("catalog_count", countKey, Duration.ofSeconds(60),
                        () -> catalogItems.count(sourceId, categoryId, null, status, genre, season, year, region));
        int totalPages = total == 0 ? 1 : (int) ((total + effectiveLimit - 1) / effectiveLimit);

        if (!useLegacyCursor) {
            int offset = (currentPage - 1) * effectiveLimit;
            List<ExternalCatalogItem> items = rewriteCovers(catalogItems.page(sourceId, categoryId, q, status,
                    effectiveSort, genre, season, year, region, null, null, offset, effectiveLimit));
            String next = currentPage < totalPages ? "page:" + (currentPage + 1) : null;
            return new CatalogItemPage(items, next, total, currentPage, effectiveLimit, totalPages);
        }

        Cursor c = decodeCursor(cursor);
        List<ExternalCatalogItem> items = rewriteCovers(catalogItems.page(sourceId, categoryId, q, status,
                effectiveSort, genre, season, year, region, c.updatedAt, c.id, null, effectiveLimit));
        String next = items.size() == effectiveLimit
                ? encodeCursor(coalesce(items.getLast().resourceUpdatedAt(), items.getLast().syncedAt()),
                        items.getLast().id())
                : null;
        return new CatalogItemPage(items, next, total, 1, effectiveLimit, totalPages);
    }

    public List<ExternalCatalogRepository.GenreStat> listGenres() {
        return hotspotCache.getOrLoad("catalog_genres", "anime-top-genres", Duration.ofMinutes(5),
                () -> catalogItems.topGenres(24));
    }

    /** 查询用户在追的收藏番剧目录 ID 集合（供 .ics 个人追番日历过滤）。 */
    public java.util.Set<String> listFavoriteCatalogIds(UUID userId) {
        return new java.util.HashSet<>(jdbc.query(
                "SELECT catalog_item_id::text FROM favorites WHERE user_id = ? AND catalog_item_id IS NOT NULL AND status = 'WATCHING'",
                (rs, i) -> rs.getString(1), userId));
    }

    private static List<ExternalCatalogItem> rewriteCovers(List<ExternalCatalogItem> items) {
        return items.stream().map(ExternalCatalogService::rewriteCover).toList();
    }

    private static ExternalCatalogItem rewriteCover(ExternalCatalogItem item) {
        if (item == null) {
            return null;
        }
        if (item.bangumiSubjectId() != null) {
            return item.withCoverUrl("/api/v1/bangumi-covers/" + item.bangumiSubjectId() + "?size=large");
        }
        if (item.coverUrl() == null || item.coverUrl().isBlank()) {
            return item;
        }
        if (item.coverUrl().startsWith("http://") || item.coverUrl().startsWith("https://")) {
            return item.withCoverUrl("/api/v1/catalog-covers/" + item.id());
        }
        return item;
    }

    // ── 目录条目详情（M11）──

    public record LineInfo(String name, int episodeCount) {}

    public record CatalogDetail(ExternalCatalogItem item, String bangumiName, String bangumiNameCn,
                                Double rating, String summary, Integer airWeekday,
                                java.time.LocalDate airDate, String airTime, String studio, String mediaType,
                                List<String> tags, List<LineInfo> lines) {}

    public CatalogDetail itemDetail(CurrentUser user, UUID itemId) {
        ExternalCatalogItem item = catalogItems.findItem(itemId).orElseThrow(
                () -> new ApiException(404, "ITEM_NOT_FOUND", "目录条目不存在。"));
        String hdCoverUrl = "";
        if (item.bangumiSubjectId() == null) {
            List<String> hdRows = jdbc.query(
                    "SELECT hd_cover_url FROM external_catalog_items WHERE id = ?",
                    (rs, i) -> rs.getString("hd_cover_url"), itemId);
            hdCoverUrl = hdRows.isEmpty() || hdRows.get(0) == null ? "" : hdRows.get(0);
            if (hdCoverUrl.isBlank() && bangumi != null) {
                try {
                    BangumiService.OnDemandEnrichResult enriched =
                            bangumi.enrichCatalogItemOnDemand(item.id(), item.name());
                    if (enriched != null) {
                        if (enriched.bangumiSubjectId() != null) {
                            item = catalogItems.findItem(itemId).orElse(item);
                        } else if (enriched.hdCoverUrl() != null) {
                            hdCoverUrl = enriched.hdCoverUrl();
                        }
                    }
                } catch (Exception ignored) {
                    // 按需高清封面补齐失败不影响详情页返回
                }
            }
        }
        // Bangumi 元数据（若已桥接，含制作公司、精确放送时刻与媒体制式）
        record B(String orig, String cn, Double rating, String summary, Integer weekday,
                 java.time.LocalDate airDate, String airTime, String studio, String mediaType, List<String> tags) {}
        List<B> rows = jdbc.query(
                "SELECT name, name_cn, rating, summary, air_weekday, air_date, air_time, studio, media_type, tags FROM bangumi_subjects WHERE subject_id = ?",
                (rs, i) -> {
                    var r = rs.getBigDecimal("rating");
                    var d = rs.getDate("air_date");
                    var arr = rs.getArray("tags");
                    List<String> tagList = arr == null ? List.of() : List.of((String[]) arr.getArray());
                    return new B(rs.getString("name"), rs.getString("name_cn"),
                            r == null ? null : r.doubleValue(), rs.getString("summary"),
                            (Integer) rs.getObject("air_weekday"),
                            d == null ? null : d.toLocalDate(),
                            rs.getString("air_time"),
                            rs.getString("studio"),
                            rs.getString("media_type"),
                            tagList);
                }, item.bangumiSubjectId() == null ? -1L : item.bangumiSubjectId());
        B b = rows.isEmpty() ? null : rows.get(0);
        List<LineInfo> lines = new ArrayList<>();
        try {
            for (var g : objectMapper.readTree(item.playGroups())) {
                int count = 0;
                for (String seg : g.path("raw").asText().split("#")) {
                    if (seg.contains("$")) count++;
                }
                if (count > 0) lines.add(new LineInfo(g.path("from").asText("线路"), count));
            }
        } catch (Exception ignored) {
            // 线路解析失败只影响详情展示
        }
        List<String> mergedTags = new ArrayList<>();
        if (b != null && b.tags() != null) {
            mergedTags.addAll(b.tags());
        }
        mergedTags.addAll(catalogItems.findTags(itemId));
        ExternalCatalogItem displayItem = rewriteCover(item);
        if (displayItem.bangumiSubjectId() == null && !hdCoverUrl.isBlank()) {
            displayItem = displayItem.withCoverUrl("/api/v1/catalog-covers/" + displayItem.id() + "?v=hd");
        }
        return new CatalogDetail(displayItem,
                b == null ? null : b.orig(),
                b == null ? null : b.cn(),
                (b != null && b.rating() != null) ? b.rating() : item.rating(),
                b == null ? null : b.summary(),
                b == null ? null : b.weekday(),
                b == null ? null : b.airDate(),
                b == null ? null : b.airTime(),
                b == null ? null : b.studio(),
                b == null ? null : b.mediaType(),
                TagNormalizer.normalize(mergedTags),
                lines);
    }

    // ── 浏览态播放（M20）：剧集挂目录条目，不建作品 ──

    public record BrowseEpisode(UUID id, String title, int ordinal, String lineName,
                                int lineRank, String episodeType) {}

    public record BrowseLine(String name, int rank, List<BrowseEpisode> episodes) {}

    public record BrowseEpisodes(ExternalCatalogItem item, List<BrowseLine> lines) {}

    /** 返回目录条目的剧集（首次按线路懒生成，不创建作品）。 */
    public BrowseEpisodes browseEpisodes(CurrentUser user, UUID itemId) {
        ExternalCatalogItem item = catalogItems.findItem(itemId).orElseThrow(
                () -> new ApiException(404, "ITEM_NOT_FOUND", "目录条目不存在。"));
        ensureBrowseEpisodes(item);
        var eps = externals.listEpisodesByCatalogItem(itemId);
        var byLine = new java.util.LinkedHashMap<String, BrowseLine>();
        for (var e : eps) {
            byLine.computeIfAbsent(e.lineName(), k -> new BrowseLine(k, e.lineRank(), new ArrayList<>()))
                    .episodes().add(new BrowseEpisode(e.id(), e.title(), e.ordinal(),
                            e.lineName(), e.lineRank(), e.episodeType()));
        }
        var lines = byLine.values().stream()
                .sorted(java.util.Comparator.comparingInt(BrowseLine::rank))
                .toList();
        return new BrowseEpisodes(rewriteCover(item), lines);
    }

    /** 浏览态播放目标：首选线路的第一集（按 rank、ordinal）。 */
    public UUID browsePlayTarget(CurrentUser user, UUID itemId) {
        ExternalCatalogItem item = catalogItems.findItem(itemId).orElseThrow(
                () -> new ApiException(404, "ITEM_NOT_FOUND", "目录条目不存在。"));
        ensureBrowseEpisodes(item);
        return externals.listEpisodesByCatalogItem(itemId).stream()
                .min(java.util.Comparator.comparingInt(ExternalEpisode::lineRank)
                        .thenComparingInt(ExternalEpisode::ordinal))
                .map(ExternalEpisode::id).orElse(null);
    }

    /** 懒生成与追更：目录条目尚无主源剧集或资源站已更新更多集数时，按最新 play_groups 幂等补齐（不影响附加备用线路）。 */
    private void ensureBrowseEpisodes(ExternalCatalogItem item) {
        List<Episode> parsed = parseEpisodes(item);
        List<ExternalEpisode> existingPrimary = externals.listEpisodesByCatalogItem(item.id()).stream()
                .filter(e -> item.sourceId().equals(e.sourceId()))
                .toList();
        if (existingPrimary.size() >= parsed.size() && hasSameKeys(existingPrimary, parsed)) {
            return;
        }
        for (var ep : parsed) {
            externals.insertCatalogEpisode(UUID.randomUUID(), item.id(), item.sourceId(),
                    ep.title(), ep.url(), ep.ordinal(), ep.lineName(), ep.lineRank(),
                    ep.episodeType());
        }
    }

    // ── 按需挂载外部 HTML 规则源备用线路（如 7sefun 七色B线）──

    public record ExtraLinesResult(boolean added, int addedEpisodes, String lineName,
                                   String matchedTitle, String message, BrowseEpisodes episodes) {}

    public ExtraLinesResult attachExtraLines(CurrentUser user, UUID itemId) {
        if (ruleEngine == null) {
            throw new ApiException(503, "RULE_ENGINE_UNAVAILABLE", "规则源引擎未启用。");
        }
        checkExtraLineRate(user);
        ExternalCatalogItem item = catalogItems.findItem(itemId).orElseThrow(
                () -> new ApiException(404, "ITEM_NOT_FOUND", "目录条目不存在。"));
        ensureBrowseEpisodes(item);

        List<ExternalSource> htmlSources = externals.listSources(true).stream()
                .filter(this::isHtmlRuleSource)
                .toList();
        if (htmlSources.isEmpty()) {
            return new ExtraLinesResult(false, 0, null, null, "暂无已启用的备用高清规则源。", browseEpisodes(user, itemId));
        }

        // 若该目录条目已挂载过全部启用的 HTML 备用源且已有剧集，直接返回避免重复打上游
        List<ExternalEpisode> currentEps = externals.listEpisodesByCatalogItem(itemId);
        List<ExternalSource> missingSources = htmlSources.stream()
                .filter(s -> currentEps.stream().noneMatch(e -> s.id().equals(e.sourceId())))
                .toList();
        if (missingSources.isEmpty()) {
            String existingLine = currentEps.stream()
                    .filter(e -> !item.sourceId().equals(e.sourceId()))
                    .map(ExternalEpisode::lineName)
                    .findFirst().orElse(null);
            return new ExtraLinesResult(false, 0, existingLine, item.name(),
                    "该番剧已加载备用高清线路。", browseEpisodes(user, itemId));
        }

        long now = System.currentTimeMillis();
        Long lastTry = extraLineItemCooldown.get(itemId);
        if (lastTry != null && now - lastTry < 15_000L) {
            return new ExtraLinesResult(false, 0, null, null,
                    "刚刚已检索过备用线路，请稍后再试。", browseEpisodes(user, itemId));
        }
        extraLineItemCooldown.put(itemId, now);

        String bangumiNameCn = loadBangumiNameCn(item.bangumiSubjectId());
        List<String> targetTitles = new ArrayList<>();
        if (bangumiNameCn != null && !bangumiNameCn.isBlank()) targetTitles.add(bangumiNameCn);
        if (item.name() != null && !item.name().isBlank()) targetTitles.add(item.name());
        List<String> keywords = RuleLineMatcher.searchKeywords(bangumiNameCn, item.name());

        int totalAdded = 0;
        String attachedLine = null;
        String matchedTitle = null;
        String lastFailure = null;
        int rank = 10;

        for (ExternalSource source : missingSources) {
            String lineName = lineNameForRuleSource(source);
            try {
                RuleEngine.SearchResultItem hit = searchBestMatch(source, keywords, targetTitles);
                if (hit == null) continue;
                List<RuleEngine.EpisodeItem> parsed = ruleEngine.parseEpisodes(
                        source.rules(), source.baseUrl(), hit.url());
                if (parsed.isEmpty()) continue;

                int ordinal = 0;
                for (RuleEngine.EpisodeItem ep : parsed) {
                    if (ep.url() == null || ep.url().isBlank()) continue;
                    ordinal++;
                    String title = (ep.title() == null || ep.title().isBlank()) ? ("第" + ordinal + "集") : ep.title();
                    String type = EXTRA_PATTERN.matcher(title).find() ? "EXTRA" : "MAIN";
                    externals.insertCatalogEpisode(UUID.randomUUID(), item.id(), source.id(),
                            title, ep.url(), ordinal, lineName, rank, type);
                }
                if (ordinal > 0) {
                    totalAdded += ordinal;
                    attachedLine = lineName;
                    matchedTitle = hit.name();
                    rank++;
                }
            } catch (Exception e) {
                lastFailure = e.getMessage();
            }
        }

        BrowseEpisodes refreshed = browseEpisodes(user, itemId);
        if (totalAdded > 0) {
            return new ExtraLinesResult(true, totalAdded, attachedLine, matchedTitle,
                    "已加载高清备用线路（共 " + totalAdded + " 集）。", refreshed);
        }
        String msg = lastFailure != null
                ? "备用源请求波动，请稍后重试。"
                : "备用源暂未收录该番剧或暂无可播直连线路。";
        return new ExtraLinesResult(false, 0, null, null, msg, refreshed);
    }

    private RuleEngine.SearchResultItem searchBestMatch(ExternalSource source,
                                                        List<String> keywords,
                                                        List<String> targetTitles) {
        for (String kw : keywords) {
            List<RuleEngine.SearchResultItem> results = ruleEngine.search(source.rules(), source.baseUrl(), kw);
            var hit = RuleLineMatcher.findBestMatch(targetTitles, results);
            if (hit.isPresent()) {
                return hit.get();
            }
        }
        return null;
    }

    private boolean isHtmlRuleSource(ExternalSource source) {
        try {
            JsonNode rules = objectMapper.readTree(source.rules());
            return "html".equalsIgnoreCase(rules.path("searchMode").asText("html"))
                    && !rules.path("episodeListSelector").asText("").isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private static String lineNameForRuleSource(ExternalSource source) {
        String base = source.baseUrl() == null ? "" : source.baseUrl().toLowerCase();
        if (base.contains("7sefun")) {
            return "7sefun_mp4";
        }
        return "ext_" + source.id().toString().substring(0, 8);
    }

    private String loadBangumiNameCn(Long subjectId) {
        if (subjectId == null) return null;
        List<String> rows = jdbc.query(
                "SELECT name_cn FROM bangumi_subjects WHERE subject_id = ?",
                (rs, i) -> rs.getString("name_cn"), subjectId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void checkExtraLineRate(CurrentUser user) {
        UUID key = user == null ? new UUID(0L, 0L) : user.id();
        long now = System.currentTimeMillis();
        var window = extraLineRateWindows.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
        synchronized (window) {
            long cutoff = now - 60_000L;
            while (!window.isEmpty() && window.peekFirst() < cutoff) window.pollFirst();
            if (window.size() >= 12) {
                throw new ApiException(429, "EXTRA_LINE_RATE_LIMITED", "加载备用线路过于频繁，请稍后再试。");
            }
            window.addLast(now);
        }
    }

    private static boolean hasSameKeys(List<ExternalEpisode> existing, List<Episode> parsed) {
        if (existing.size() != parsed.size()) {
            return false;
        }
        java.util.Set<String> keys = new java.util.HashSet<>(existing.size() * 2);
        for (ExternalEpisode e : existing) {
            keys.add(e.lineName() + "#" + e.ordinal() + "#" + e.episodeUrl());
        }
        for (Episode p : parsed) {
            if (!keys.contains(p.lineName() + "#" + p.ordinal() + "#" + p.url())) {
                return false;
            }
        }
        return true;
    }

    // ── 物化 ──

    public record Materialized(UUID workId, boolean created) {}

    public Materialized materialize(CurrentUser user, UUID itemId) {
        ExternalCatalogItem item = catalogItems.findItem(itemId).orElseThrow(
                () -> new ApiException(404, "ITEM_NOT_FOUND", "目录条目不存在。"));
        if (item.workId() != null) {
            return new Materialized(item.workId(), false);
        }
        UUID workId = tx.execute(status -> {
            // 行锁防并发重复物化（UNIQUE(source_id,vod_id) 行）
            var fresh = catalogItems.findItem(itemId).orElseThrow();
            if (fresh.workId() != null) return fresh.workId();
            UUID wid = catalog.insertWork(user.id(), fresh.name(), fresh.remarks());
            // 已有浏览态剧集则直接收编，避免重复解析
            if (externals.adoptEpisodes(itemId, wid) == 0) {
                for (var ep : parseEpisodes(fresh)) {
                    externals.insertEpisode(UUID.randomUUID(), wid, fresh.sourceId(),
                            ep.title(), ep.url(), ep.ordinal(), ep.lineName(), ep.lineRank(),
                            ep.episodeType());
                }
            }
            catalogItems.markMaterialized(itemId, wid);
            return wid;
        });
        return new Materialized(workId, true);
    }

    /**
     * 解析全部线路（M11）：每组按名称排序（含 m3u8 的排前），组内 名称$url#… 逐条。
     * 同一集号在各线路各存一行；EXTRA 识别 PV/预告/特典/OP/ED 等非正片。
     */
    record Episode(String title, String url, int ordinal, String lineName,
                   int lineRank, String episodeType) {}

    private static final java.util.regex.Pattern EXTRA_PATTERN = java.util.regex.Pattern.compile(
            "PV|预告|特典|花絮|OP|ED|片头|片尾|番外|彩蛋|CM|MENU|映像",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private List<Episode> parseEpisodes(ExternalCatalogItem item) {
        List<Episode> out = parsePlayGroups(objectMapper, item.playGroups());
        if (out.isEmpty()) {
            throw new ApiException(422, "NO_EPISODES", "该条目没有可解析的剧集地址");
        }
        return out;
    }

    static List<Episode> parsePlayGroups(ObjectMapper mapper, String playGroupsJson) {
        List<Episode> out = new ArrayList<>();
        try {
            var groups = mapper.readTree(playGroupsJson);
            var ordered = new ArrayList<JsonNode>();
            for (JsonNode g : groups) {
                if (g.path("from").asText().toLowerCase().contains("m3u8")) ordered.add(g);
            }
            for (JsonNode g : groups) {
                if (!ordered.contains(g)) ordered.add(g);
            }
            int rank = 0;
            for (JsonNode g : ordered) {
                String lineName = g.path("from").asText("line" + rank);
                int ordinal = 0;
                for (String seg : g.path("raw").asText().split("#")) {
                    String[] kv = seg.split("\\$", 2);
                    if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) continue;
                    ordinal++;
                    String type = EXTRA_PATTERN.matcher(kv[0]).find() ? "EXTRA" : "MAIN";
                    out.add(new Episode(kv[0], kv[1], ordinal, lineName, rank, type));
                }
                rank++;
            }
        } catch (Exception e) {
            throw new ApiException(422, "PLAY_GROUPS_INVALID", "线路数据无法解析：" + e.getMessage());
        }
        return out;
    }

    private void requireAdmin(CurrentUser user) {
        if (!user.isAdmin()) {
            throw new ApiException(403, "FORBIDDEN", "只有管理员可以管理资源目录。");
        }
    }

    // ── 游标（resource_updated_at, id）──

    private record Cursor(Instant updatedAt, UUID id) {}

    private static String encodeCursor(Instant t, UUID id) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((t.toString() + "|" + id).getBytes());
    }

    private static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            // 首页哨兵：PG timestamptz 上限内的"最大"值（Long.MAX_VALUE/2 会溢出 PG 范围）
            return new Cursor(Instant.parse("9999-12-31T23:59:59Z"), new UUID(Long.MAX_VALUE, Long.MAX_VALUE));
        }
        try {
            String s = new String(java.util.Base64.getUrlDecoder().decode(cursor));
            int cut = s.lastIndexOf('|');
            return new Cursor(Instant.parse(s.substring(0, cut)), UUID.fromString(s.substring(cut + 1)));
        } catch (Exception e) {
            throw new ApiException(400, "BAD_CURSOR", "分页游标无效。");
        }
    }

    private static Instant coalesce(Instant a, Instant b) {
        return a != null ? a : b;
    }
}
