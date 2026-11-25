package com.mangashelf.external.manga;

import com.mangashelf.config.SharedCacheHelper;
import com.mangashelf.identity.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 多源聚合推荐与全流漫画搜索（M25/M26 + revamp-discovery-search-index-and-nav）：
 * - 发现流：中文源并行抓取 + 单源熔断冷却 + 可读性加权 RRF + 跨源共识 + 同作者打散 + 异步目录沉淀；
 * - 全流搜索：并发扇出全部 SEARCH 中文源 + manga_catalog_items 目录库 + 有权访问的站内本地漫画，
 *   单源 5s 超时隔离、跨流同作聚簇合并，返回多流切换入口与各流健康状态。
 */
@Service
public class MangaDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(MangaDiscoveryService.class);
    private static final int RRF_K = 60;
    private static final double READABLE_BOOST = 1.25;
    private static final double CONSENSUS_BONUS = 0.004;
    /** 发现流单源超时（秒）。 */
    private static final long TIMEOUT_SECONDS = 15;
    /** 全流搜索单源硬超时（秒）。 */
    private static final long SEARCH_TIMEOUT_SECONDS = 5;
    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final int CIRCUIT_FAILURE_THRESHOLD = 2;
    private static final long CIRCUIT_COOLDOWN_MS = 5 * 60 * 1000L;
    private static final Pattern HAN = Pattern.compile("[\\u4e00-\\u9fff]");
    /** 近似标题合并阈值：只合并同一标题的写法差异，版本后缀（全彩版等）不误合并。 */
    private static final double MERGE_THRESHOLD = 0.9;

    /** 聚合条目（M26）：摊平的 RankItem 字段 + 同作合并信息（sourceCount>1 表示跨源同作）。 */
    public record DiscoveryItem(String source, String id, String title, List<String> authors,
                                String coverUrl, boolean finished, String latestLabel,
                                boolean readable, String externalUrl,
                                String readSource, String readId,
                                int sourceCount, List<String> sources) {

        static DiscoveryItem of(MangaSource.RankItem item, int sourceCount, List<String> sources) {
            return new DiscoveryItem(item.source(), item.id(), item.title(), item.authors(),
                    item.coverUrl(), item.finished(), item.latestLabel(), item.readable(),
                    item.externalUrl(), item.readSource(), item.readId(), sourceCount, sources);
        }
    }

    /** 发现流行：sourceId/sourceName 为空表示综合榜。 */
    public record Row(String id, String name, String sourceId, String sourceName,
                      List<DiscoveryItem> items) {}

    public record Result(List<Row> rows, List<String> errors) {}

    /** 全流搜索：单条流入口。 */
    public record StreamEntry(String source, String sourceName, String id, boolean readable,
                              String coverUrl, String remarks, UUID workId) {}

    /** 全流搜索：跨流聚簇后的统一条目。 */
    public record UnifiedSearchItem(String title, String altTitle, String coverUrl,
                                    String primarySource, String primarySourceName, String primaryId,
                                    UUID workId, boolean readable, String remarks,
                                    int sourceCount, List<StreamEntry> streams) {}

    /** 全流搜索：单流执行状态。 */
    public record SourceStatus(String source, String name, int count, boolean ok,
                               String error, long durationMs) {}

    /** 全流搜索响应体。 */
    public record UnifiedSearchResult(String query, List<UnifiedSearchItem> items,
                                      List<SourceStatus> sourceStatus) {}

    private record CacheEntry(Result result, long expiresAt) {}

    private record Scored(String key, MangaSource.RankItem item, double score) {}

    private record SourceCircuit(int failures, long cooldownUntil, String lastError) {}

    private final MangaSourceRegistry registry;
    private final MangaSectionsService sectionsService;
    private final SharedCacheHelper shared;
    private final MangaCatalogRepository catalog;
    private final JdbcTemplate jdbc;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SourceCircuit> circuits = new ConcurrentHashMap<>();

    @Autowired
    public MangaDiscoveryService(MangaSourceRegistry registry,
                                 MangaSectionsService sectionsService,
                                 ObjectProvider<SharedCacheHelper> shared,
                                 ObjectProvider<MangaCatalogRepository> catalog,
                                 ObjectProvider<JdbcTemplate> jdbc) {
        this.registry = registry;
        this.sectionsService = sectionsService;
        this.shared = shared == null ? null : shared.getIfAvailable();
        this.catalog = catalog == null ? null : catalog.getIfAvailable();
        this.jdbc = jdbc == null ? null : jdbc.getIfAvailable();
    }

    /** 兼容既有单元测试构造器。 */
    public MangaDiscoveryService(MangaSourceRegistry registry, MangaSectionsService sectionsService,
                                 ObjectProvider<SharedCacheHelper> shared) {
        this(registry, sectionsService, shared, null, null);
    }

    // ── 源健康熔断 ──

    boolean isCoolingDown(String sourceId, long now) {
        SourceCircuit c = circuits.get(sourceId);
        return c != null && c.cooldownUntil() > now;
    }

    String cooldownMessage(String sourceId) {
        SourceCircuit c = circuits.get(sourceId);
        return c == null || c.lastError() == null ? "暂时不可用（冷却中）" : "冷却跳过（上次：" + c.lastError() + "）";
    }

    void recordSuccess(String sourceId) {
        circuits.remove(sourceId);
    }

    void recordFailure(String sourceId, String reason, long now) {
        circuits.compute(sourceId, (k, prev) -> {
            int nextFailures = (prev == null ? 0 : prev.failures()) + 1;
            long cooldownUntil = nextFailures >= CIRCUIT_FAILURE_THRESHOLD
                    ? now + CIRCUIT_COOLDOWN_MS : 0L;
            return new SourceCircuit(nextFailures, cooldownUntil, reason);
        });
    }

    // ── 多源发现流 ──

    public Result discover(int limit) throws Exception {
        int size = Math.max(1, Math.min(limit, 30));
        String key = "v4:" + size;
        CacheEntry hit = cache.get(key);
        if (hit != null && hit.expiresAt() > System.currentTimeMillis()) {
            return hit.result();
        }
        if (shared != null) {
            var redisHit = shared.get("ms:v3:discovery:" + size);
            if (redisHit.isPresent()) {
                var stored = shared.decodePublic(redisHit.get(), StoredResult.class);
                if (stored != null && stored.rows() != null) {
                    Result result = new Result(stored.rows(), stored.errors());
                    cache.put(key, new CacheEntry(result, System.currentTimeMillis() + TTL_MS));
                    return result;
                }
            }
        }

        List<MangaSource> sources = registry.all().stream()
                .filter(s -> s.capabilities().contains(MangaSource.Capability.RANK))
                .filter(s -> "zh".equals(s.language()))
                .toList();

        long now = System.currentTimeMillis();
        record Fetched(MangaSource source, List<MangaSource.Section> sections) {}
        List<MangaSource> activeSources = new ArrayList<>();
        List<Future<Fetched>> futures = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (MangaSource s : sources) {
            if (isCoolingDown(s.id(), now)) {
                errors.add(s.name() + " " + cooldownMessage(s.id()));
                continue;
            }
            activeSources.add(s);
            futures.add(executor.submit(() -> new Fetched(s, sectionsService.get(s, size))));
        }

        List<Fetched> fetched = new ArrayList<>();
        long discoverDeadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
        for (int i = 0; i < futures.size(); i++) {
            MangaSource s = activeSources.get(i);
            long remainingMs = Math.max(1L, discoverDeadline - System.currentTimeMillis());
            try {
                Fetched f = futures.get(i).get(remainingMs, TimeUnit.MILLISECONDS);
                fetched.add(f);
                recordSuccess(s.id());
            } catch (java.util.concurrent.TimeoutException e) {
                futures.get(i).cancel(true);
                recordFailure(s.id(), "请求超时", System.currentTimeMillis());
                errors.add(s.name() + " 请求超时");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                String msg = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                recordFailure(s.id(), msg, System.currentTimeMillis());
                errors.add(s.name() + " 请求失败：" + msg);
                log.warn("discovery source {} failed: {}", s.id(), msg);
            }
        }

        Set<String> zhSourceIds = fetched.stream()
                .map(f -> f.source().id()).collect(java.util.stream.Collectors.toSet());
        Map<String, String> sourceNames = registry.all().stream()
                .collect(java.util.stream.Collectors.toMap(MangaSource::id, MangaSource::name));
        List<List<MangaSource.RankItem>> rankedLists = new ArrayList<>();
        for (Fetched f : fetched) {
            for (MangaSource.Section section : f.sections()) {
                if (!section.items().isEmpty()) {
                    rankedLists.add(sanitize(section.items(), zhSourceIds));
                }
            }
        }

        // 异步将发现流条目沉淀到漫画目录库，反哺分类索引与推荐（不阻塞首屏响应）
        if (catalog != null && !fetched.isEmpty()) {
            List<FetchedSectionSource> snapshot = fetched.stream()
                    .map(f -> new FetchedSectionSource(f.source().id(), f.sections()))
                    .toList();
            executor.submit(() -> persistDiscoveryToCatalog(snapshot));
        }

        List<Row> rows = new ArrayList<>();
        List<DiscoveryItem> allReadable = fuse(rankedLists, size * 2, sourceNames).stream()
                .filter(it -> it.readable() || (it.readId() != null && !it.readId().isBlank()))
                .limit(size)
                .toList();
        rows.add(new Row("all", "综合热榜", null, null, allReadable));
        for (Fetched f : fetched) {
            for (MangaSource.Section section : f.sections()) {
                List<DiscoveryItem> items = sanitize(section.items(), zhSourceIds).stream()
                        .filter(it -> isChineseTitle(it.title()))
                        .filter(it -> it.readable() || (it.readId() != null && !it.readId().isBlank()))
                        .map(it -> DiscoveryItem.of(it, 1, List.of()))
                        .toList();
                if (items.isEmpty()) continue;
                rows.add(new Row(f.source().id() + ":" + section.id(), section.name(),
                        f.source().id(), f.source().name(), items));
            }
        }

        Result result = new Result(rows, errors);
        long ttl = errors.isEmpty() ? TTL_MS : 60_000L;
        cache.put(key, new CacheEntry(result, System.currentTimeMillis() + ttl));
        if (shared != null && errors.isEmpty()) {
            try {
                shared.putJson("ms:v3:discovery:" + size,
                        shared.encodePublic(new StoredResult(rows, errors)),
                        java.time.Duration.ofMillis(TTL_MS));
            } catch (Exception e) {
                log.warn("discovery cache put failed: {}", e.getMessage());
            }
        }
        return result;
    }

    private record FetchedSectionSource(String sourceId, List<MangaSource.Section> sections) {}

    private void persistDiscoveryToCatalog(List<FetchedSectionSource> fetchedList) {
        if (catalog == null) return;
        for (FetchedSectionSource f : fetchedList) {
            for (MangaSource.Section section : f.sections()) {
                for (MangaSource.RankItem item : section.items()) {
                    if (item.id() == null || item.id().isBlank() || item.title() == null || item.title().isBlank()) {
                        continue;
                    }
                    if (!item.readable() && (item.readId() == null || item.readId().isBlank())) {
                        continue;
                    }
                    String remarks = item.latestLabel() != null && !item.latestLabel().isBlank()
                            ? item.latestLabel().trim()
                            : (item.finished() ? "已完结" : "");
                    String authors = item.authors() == null ? "" : String.join(" / ", item.authors());
                    String targetSource = (item.readSource() != null && !item.readSource().isBlank())
                            ? item.readSource() : f.sourceId();
                    String targetId = (item.readId() != null && !item.readId().isBlank())
                            ? item.readId() : item.id();
                    try {
                        catalog.upsert(UUID.randomUUID(), targetSource, targetId, item.title(),
                                authors, item.coverUrl() == null ? "" : item.coverUrl(),
                                remarks, List.of(), null);
                    } catch (Exception ignored) {
                        // 异步沉淀失败不影响发现流响应
                    }
                }
            }
        }
    }

    // ── 全流漫画聚合搜索 ──

    /**
     * 跨流漫画搜索：并发检索所有启用且支持 SEARCH 的中文在线源 + 目录库（覆盖无实时搜索源如哔哩）
     * + 有权访问的站内本地漫画；单源 5s 超时隔离 + 熔断保护 + 跨流聚簇去重。
     */
    public UnifiedSearchResult searchAllStreams(String rawQuery, String sourceFilter,
                                                int limit, CurrentUser user) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            return new UnifiedSearchResult("", List.of(), List.of());
        }
        int effectiveLimit = Math.min(Math.max(limit, 1), 60);
        String filter = (sourceFilter == null || sourceFilter.isBlank()) ? "all" : sourceFilter.trim();
        boolean allStreams = "all".equalsIgnoreCase(filter);

        Map<String, String> sourceNames = new LinkedHashMap<>();
        sourceNames.put("local", "本地书库");
        for (MangaSource s : registry.all()) {
            sourceNames.put(s.id(), s.name());
        }
        Set<String> readableSources = registry.all().stream()
                .filter(s -> s.capabilities().contains(MangaSource.Capability.READ))
                .map(MangaSource::id)
                .collect(java.util.stream.Collectors.toSet());

        List<MangaSource> onlineSources = registry.all().stream()
                .filter(s -> "zh".equals(s.language()))
                .filter(s -> s.capabilities().contains(MangaSource.Capability.SEARCH))
                .filter(s -> allStreams || s.id().equalsIgnoreCase(filter))
                .toList();

        record RawHit(String title, String altTitle, String coverUrl, String source, String sourceName,
                      String id, boolean readable, String remarks, UUID workId, int rankIndex) {}
        record StreamOutcome(SourceStatus status, List<RawHit> hits) {}

        long now = System.currentTimeMillis();
        List<Future<StreamOutcome>> futures = new ArrayList<>();
        List<String> taskIds = new ArrayList<>();
        List<String> taskNames = new ArrayList<>();
        List<SourceStatus> immediateStatus = new ArrayList<>();

        // 1. 并发扇出在线 SEARCH 源
        for (MangaSource s : onlineSources) {
            if (isCoolingDown(s.id(), now)) {
                immediateStatus.add(new SourceStatus(s.id(), s.name(), 0, false, cooldownMessage(s.id()), 0));
                continue;
            }
            taskIds.add(s.id());
            taskNames.add(s.name());
            futures.add(executor.submit(() -> {
                long t0 = System.currentTimeMillis();
                List<MangaSource.SearchItem> found = s.search(query, "zh", Math.min(effectiveLimit, 24));
                long dur = System.currentTimeMillis() - t0;
                boolean canRead = readableSources.contains(s.id());
                List<RawHit> hits = new ArrayList<>();
                for (int i = 0; i < found.size(); i++) {
                    MangaSource.SearchItem it = found.get(i);
                    if (it.id() == null || it.id().isBlank() || it.title() == null || it.title().isBlank()) continue;
                    hits.add(new RawHit(it.title(), it.altTitle() == null ? "" : it.altTitle(),
                            it.coverUrl(), s.id(), s.name(), it.id(), canRead,
                            it.year() == null ? "" : it.year(), null, i));
                }
                if (catalog != null && !found.isEmpty()) {
                    List<MangaSource.SearchItem> snapshot = List.copyOf(found);
                    executor.submit(() -> persistSearchHits(snapshot));
                }
                return new StreamOutcome(new SourceStatus(s.id(), s.name(), hits.size(), true, null, dur), hits);
            }));
        }

        // 2. 目录库流（覆盖 bilibili 等无 SEARCH 源以及历史沉淀条目）
        if (catalog != null && !"local".equalsIgnoreCase(filter)) {
            taskIds.add("catalog");
            taskNames.add("目录缓存");
            futures.add(executor.submit(() -> {
                long t0 = System.currentTimeMillis();
                List<MangaCatalogRepository.MangaCatalogItem> rows = catalog.search(query, allStreams ? null : filter, 30);
                long dur = System.currentTimeMillis() - t0;
                List<RawHit> hits = new ArrayList<>();
                for (int i = 0; i < rows.size(); i++) {
                    var r = rows.get(i);
                    String srcName = sourceNames.getOrDefault(r.source(), r.source());
                    boolean canRead = readableSources.contains(r.source());
                    String remarks = r.remarks() != null && !r.remarks().isBlank()
                            ? r.remarks()
                            : (r.episodeCount() != null ? r.episodeCount() + " 话" : "");
                    hits.add(new RawHit(r.name(), r.altTitle() == null ? "" : r.altTitle(),
                            r.coverUrl() == null || r.coverUrl().isBlank() ? null : r.coverUrl(),
                            r.source(), srcName, r.sourceItemId(), canRead, remarks, null, i + 3));
                }
                return new StreamOutcome(new SourceStatus("catalog", "目录缓存", hits.size(), true, null, dur), hits);
            }));
        }

        // 3. 本地书库流（仅返回有权访问的作品）
        if (jdbc != null && (allStreams || "local".equalsIgnoreCase(filter))) {
            taskIds.add("local");
            taskNames.add("本地书库");
            futures.add(executor.submit(() -> {
                long t0 = System.currentTimeMillis();
                List<LocalWorkRow> rows = searchLocalManga(query, user, 12);
                long dur = System.currentTimeMillis() - t0;
                List<RawHit> hits = new ArrayList<>();
                for (int i = 0; i < rows.size(); i++) {
                    var r = rows.get(i);
                    String remarks = r.chapterCount() > 0 ? r.chapterCount() + " 话" : "本地漫画";
                    hits.add(new RawHit(r.title(), r.description(), null, "local", "本地书库",
                            r.id().toString(), true, remarks, r.id(), i));
                }
                return new StreamOutcome(new SourceStatus("local", "本地书库", hits.size(), true, null, dur), hits);
            }));
        }

        List<SourceStatus> statuses = new ArrayList<>(immediateStatus);
        List<RawHit> allHits = new ArrayList<>();
        // 共享总截止时间：并行扇出后整体最多等待 SEARCH_TIMEOUT_SECONDS，避免多源串行叠加超时
        long searchDeadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(SEARCH_TIMEOUT_SECONDS);
        for (int i = 0; i < futures.size(); i++) {
            String id = taskIds.get(i);
            String name = taskNames.get(i);
            long remainingMs = Math.max(1L, searchDeadline - System.currentTimeMillis());
            try {
                StreamOutcome out = futures.get(i).get(remainingMs, TimeUnit.MILLISECONDS);
                statuses.add(out.status());
                allHits.addAll(out.hits());
                if (!"catalog".equals(id) && !"local".equals(id)) {
                    recordSuccess(id);
                }
            } catch (java.util.concurrent.TimeoutException e) {
                futures.get(i).cancel(true);
                if (!"catalog".equals(id) && !"local".equals(id)) {
                    recordFailure(id, "搜索超时", System.currentTimeMillis());
                }
                statuses.add(new SourceStatus(id, name, 0, false, "搜索超时（>" + SEARCH_TIMEOUT_SECONDS + "s）",
                        SEARCH_TIMEOUT_SECONDS * 1000L));
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                String msg = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                if (!"catalog".equals(id) && !"local".equals(id)) {
                    recordFailure(id, msg, System.currentTimeMillis());
                }
                statuses.add(new SourceStatus(id, name, 0, false, msg, 0));
                log.warn("manga search stream {} failed: {}", id, msg);
            }
        }

        // 跨流聚簇合并与打分排序
        String normQuery = normalizeTitle(query);
        List<SearchCluster> clusters = new ArrayList<>();
        for (RawHit h : allHits) {
            String key = normalizeTitle(h.title());
            if (key.isEmpty()) continue;
            SearchCluster target = null;
            for (SearchCluster c : clusters) {
                if (c.key.equals(key) || MangaMatcher.similarity(c.key, key) >= MERGE_THRESHOLD) {
                    target = c;
                    break;
                }
            }
            StreamEntry entry = new StreamEntry(h.source(), h.sourceName(), h.id(), h.readable(),
                    h.coverUrl(), h.remarks(), h.workId());
            if (target == null) {
                target = new SearchCluster(key, h.title(), h.altTitle(), h.coverUrl(), entry, h.rankIndex());
                clusters.add(target);
            } else {
                target.addStream(entry, h.title(), h.altTitle(), h.coverUrl(), h.rankIndex());
            }
        }

        List<UnifiedSearchItem> merged = clusters.stream()
                .sorted(Comparator.comparingDouble((SearchCluster c) -> c.score(normQuery)).reversed())
                .limit(effectiveLimit)
                .map(SearchCluster::toItem)
                .toList();

        return new UnifiedSearchResult(query, merged, statuses);
    }

    private void persistSearchHits(List<MangaSource.SearchItem> items) {
        if (catalog == null) return;
        for (MangaSource.SearchItem item : items) {
            if (item.id() == null || item.id().isBlank() || item.title() == null || item.title().isBlank()) continue;
            try {
                catalog.upsert(UUID.randomUUID(), item.source(), item.id(), item.title(),
                        item.altTitle() == null ? "" : item.altTitle(),
                        item.coverUrl() == null ? "" : item.coverUrl(),
                        item.year() == null ? "" : item.year(), List.of(), null);
            } catch (Exception ignored) {
                // 异步沉淀失败不影响主搜索
            }
        }
    }

    private record LocalWorkRow(UUID id, String title, String description, int chapterCount) {}

    private List<LocalWorkRow> searchLocalManga(String query, CurrentUser user, int limit) {
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String pattern = "%" + escaped + "%";
        if (user == null || user.isGuest()) {
            return jdbc.query("""
                    SELECT w.id, w.title, COALESCE(w.description, '') AS description,
                           (SELECT count(*) FROM chapters c WHERE c.work_id = w.id) AS chapter_count
                      FROM works w
                     WHERE w.visibility = 'PUBLIC' AND w.status = 'PUBLISHED'
                       AND w.title ILIKE ? ESCAPE '\\'
                       AND EXISTS (SELECT 1 FROM chapters c WHERE c.work_id = w.id)
                     ORDER BY w.updated_at DESC
                     LIMIT ?
                    """, (rs, i) -> new LocalWorkRow(rs.getObject("id", UUID.class), rs.getString("title"),
                    rs.getString("description"), rs.getInt("chapter_count")), pattern, limit);
        }
        return jdbc.query("""
                SELECT w.id, w.title, COALESCE(w.description, '') AS description,
                       (SELECT count(*) FROM chapters c WHERE c.work_id = w.id) AS chapter_count
                  FROM works w
                 WHERE (w.owner_id = ? OR (w.visibility = 'PUBLIC' AND w.status = 'PUBLISHED'))
                   AND w.title ILIKE ? ESCAPE '\\'
                   AND EXISTS (SELECT 1 FROM chapters c WHERE c.work_id = w.id)
                 ORDER BY w.updated_at DESC
                 LIMIT ?
                """, (rs, i) -> new LocalWorkRow(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getString("description"), rs.getInt("chapter_count")), user.id(), pattern, limit);
    }

    private static final class SearchCluster {
        final String key;
        String title;
        String altTitle;
        String coverUrl;
        StreamEntry primary;
        int bestRank;
        final Map<String, StreamEntry> bySource = new LinkedHashMap<>();

        SearchCluster(String key, String title, String altTitle, String coverUrl,
                      StreamEntry first, int rankIndex) {
            this.key = key;
            this.title = title;
            this.altTitle = altTitle == null ? "" : altTitle;
            this.coverUrl = coverUrl;
            this.primary = first;
            this.bestRank = rankIndex;
            this.bySource.put(first.source(), first);
        }

        void addStream(StreamEntry entry, String candTitle, String candAlt, String candCover, int rankIndex) {
            StreamEntry prev = bySource.get(entry.source());
            if (prev == null || (prev.coverUrl() == null && entry.coverUrl() != null)) {
                bySource.put(entry.source(), entry);
            }
            if ((coverUrl == null || coverUrl.isBlank()) && candCover != null && !candCover.isBlank()) {
                coverUrl = candCover;
            }
            if ((altTitle == null || altTitle.isBlank()) && candAlt != null && !candAlt.isBlank()) {
                altTitle = candAlt;
            }
            bestRank = Math.min(bestRank, rankIndex);
            if (isBetterPrimary(entry, primary)) {
                primary = entry;
                if (candTitle != null && !candTitle.isBlank()) {
                    title = candTitle;
                }
            }
        }

        private static boolean isBetterPrimary(StreamEntry cand, StreamEntry cur) {
            if (cand.workId() != null && cur.workId() == null) return true;
            if (cur.workId() != null && cand.workId() == null) return false;
            if (cand.readable() != cur.readable()) return cand.readable();
            boolean candCover = cand.coverUrl() != null && !cand.coverUrl().isBlank();
            boolean curCover = cur.coverUrl() != null && !cur.coverUrl().isBlank();
            return candCover && !curCover;
        }

        double score(String normQuery) {
            double s = 0.0;
            if (key.equals(normQuery)) {
                s += 100.0;
            } else if (!normQuery.isEmpty() && key.startsWith(normQuery)) {
                s += 60.0;
            } else if (!normQuery.isEmpty() && key.contains(normQuery)) {
                s += 35.0;
            } else {
                s += 25.0 * MangaMatcher.similarity(key, normQuery);
            }
            s += Math.min(bySource.size(), 4) * 8.0;
            if (primary.readable()) s += 6.0;
            if (coverUrl != null && !coverUrl.isBlank()) s += 2.0;
            s += 10.0 / (10.0 + Math.max(bestRank, 0));
            return s;
        }

        UnifiedSearchItem toItem() {
            List<StreamEntry> list = List.copyOf(bySource.values());
            String bestCover = coverUrl;
            if (bestCover == null || bestCover.isBlank()) {
                bestCover = list.stream().map(StreamEntry::coverUrl)
                        .filter(u -> u != null && !u.isBlank()).findFirst().orElse(null);
            }
            return new UnifiedSearchItem(title, altTitle, bestCover,
                    primary.source(), primary.sourceName(), primary.id(),
                    primary.workId(), primary.readable(), primary.remarks(),
                    list.size(), list);
        }
    }

    /** Redis 往返载体（redis-shared-cache-and-storage）。 */
    record StoredResult(List<Row> rows, List<String> errors) {}

    /**
     * RRF 融合 + 作品聚簇 + 可读性加权 + 共识加成 + 作者多样性打散：
     * - 站内可读条目 RRF 贡献乘以 1.25；
     * - 跨源共识叠加小幅共振分；
     * - 同作者不连续堆叠出现。
     */
    static List<DiscoveryItem> fuse(List<List<MangaSource.RankItem>> rankedLists, int limit,
                                    Map<String, String> sourceNames) {
        List<Cluster> clusters = new ArrayList<>();
        List<Scored> scored = new ArrayList<>();
        for (List<MangaSource.RankItem> list : rankedLists) {
            for (int i = 0; i < list.size(); i++) {
                MangaSource.RankItem item = list.get(i);
                if (!isChineseTitle(item.title())) continue;
                String key = normalizeTitle(item.title());
                if (key.isEmpty()) continue;
                double weight = item.readable() ? READABLE_BOOST : 1.0;
                scored.add(new Scored(key, item, weight / (RRF_K + i + 1)));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        for (Scored s : scored) {
            Cluster target = null;
            for (Cluster c : clusters) {
                if (c.key.equals(s.key())
                        || MangaMatcher.similarity(c.key, s.key()) >= MERGE_THRESHOLD) {
                    target = c;
                    break;
                }
            }
            if (target == null) {
                clusters.add(new Cluster(s.key(), s.item(), s.score(),
                        new LinkedHashMap<>(Map.of(s.item().source(), s.item().source()))));
            } else {
                target.score += s.score();
                if (!target.item.readable() && s.item().readable()) {
                    target.item = s.item();
                } else if ((target.item.coverUrl() == null || target.item.coverUrl().isBlank())
                        && s.item().coverUrl() != null && !s.item().coverUrl().isBlank()
                        && target.item.readable() == s.item().readable()) {
                    target.item = s.item();
                }
                target.sources.putIfAbsent(s.item().source(), s.item().source());
            }
        }
        List<Cluster> ordered = clusters.stream()
                .sorted((a, b) -> Double.compare(b.finalScore(), a.finalScore()))
                .toList();

        List<Cluster> diversified = diversifyByAuthor(ordered, Math.max(1, limit));
        return diversified.stream()
                .map(c -> DiscoveryItem.of(c.item, c.sources.size(),
                        c.sources.keySet().stream().map(id -> sourceNames.getOrDefault(id, id)).toList()))
                .toList();
    }

    /** 作者多样性打散：避免同一作者连续霸屏。 */
    private static List<Cluster> diversifyByAuthor(List<Cluster> ordered, int limit) {
        List<Cluster> pool = new ArrayList<>(ordered);
        List<Cluster> out = new ArrayList<>(Math.min(limit, pool.size()));
        String lastAuthor = null;
        while (out.size() < limit && !pool.isEmpty()) {
            int pickIdx = 0;
            if (lastAuthor != null) {
                for (int i = 0; i < pool.size(); i++) {
                    String candAuthor = primaryAuthor(pool.get(i).item);
                    if (candAuthor == null || !candAuthor.equals(lastAuthor)) {
                        pickIdx = i;
                        break;
                    }
                }
            }
            Cluster picked = pool.remove(pickIdx);
            out.add(picked);
            lastAuthor = primaryAuthor(picked.item);
        }
        return out;
    }

    private static String primaryAuthor(MangaSource.RankItem item) {
        if (item == null || item.authors() == null || item.authors().isEmpty()) return null;
        String first = item.authors().getFirst();
        if (first == null) return null;
        String norm = first.trim().toLowerCase(Locale.ROOT);
        return norm.isEmpty() ? null : norm;
    }

    /** 作品聚簇：代表 key + 展示条目 + 累计得分 + 成员源。 */
    private static final class Cluster {
        final String key;
        MangaSource.RankItem item;
        double score;
        final Map<String, String> sources;

        Cluster(String key, MangaSource.RankItem item, double score, Map<String, String> sources) {
            this.key = key;
            this.item = item;
            this.score = score;
            this.sources = sources;
        }

        double finalScore() {
            return score + Math.max(0, sources.size() - 1) * CONSENSUS_BONUS;
        }
    }

    /**
     * 只保留中文源站内阅读入口：哔哩榜单跨源桥接到 MangaDex 的条目降级为站外
     * （打开原站），避免从中文首页进入非中文源阅读。
     */
    static List<MangaSource.RankItem> sanitize(List<MangaSource.RankItem> items,
                                               Set<String> zhSourceIds) {
        return items.stream().map(it -> {
            boolean foreignRead = it.readId() != null && it.readSource() != null
                    && !zhSourceIds.contains(it.readSource());
            if (!foreignRead) return it;
            return new MangaSource.RankItem(it.source(), it.id(), it.title(), it.authors(),
                    it.coverUrl(), it.finished(), it.latestLabel(), false, it.externalUrl(),
                    null, null);
        }).toList();
    }

    /** 标题归一化：NFKC + 小写 + 去空白/标点/符号（保留括号内文字，区分全彩版等）。 */
    static String normalizeTitle(String title) {
        if (title == null) return "";
        String v = Normalizer.normalize(title, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return v.replaceAll("[\\s\\p{P}\\p{S}]", "");
    }

    /** 中文内容过滤：标题必须含汉字。 */
    static boolean isChineseTitle(String title) {
        return title != null && HAN.matcher(title).find();
    }
}
