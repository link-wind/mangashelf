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

    public ExternalCatalogService(ExternalCatalogRepository catalogItems, ExternalRepository externals,
                                  CatalogRepository catalog, JobCreationService jobCreation,
                                  TransactionTemplate tx, ObjectMapper objectMapper,
                                  org.springframework.jdbc.core.JdbcTemplate jdbc,
                                  HotspotCache hotspotCache) {
        this.catalogItems = catalogItems;
        this.externals = externals;
        this.catalog = catalog;
        this.jobCreation = jobCreation;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.hotspotCache = hotspotCache;
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
        return listItems(cursor, limit, q, sourceId, categoryId, status, sort, genre, season, null, page);
    }

    public CatalogItemPage listItems(String cursor, int limit, String q, UUID sourceId,
                                     Integer categoryId, String status, String sort, String genre,
                                     Boolean season, String year, Integer page) {
        int effectiveLimit = Math.min(Math.max(limit, 1), 60);
        boolean hasQuery = q != null && !q.isBlank();
        String effectiveSort = (sort == null || sort.isBlank()) ? "updated" : sort;
        boolean pagedMode = page != null || "episodes".equals(effectiveSort) || "rating".equals(effectiveSort);
        String countKey = "catalog-count:" + sourceId + ":" + categoryId + ":" + status + ":"
                + genre + ":" + season + ":" + year + ":" + (hasQuery ? q.trim() : "");

        if (!hasQuery && !pagedMode && (cursor == null || cursor.isBlank())) {
            String pageKey = "catalog-page:" + effectiveLimit + ":" + sourceId + ":" + categoryId
                    + ":" + status + ":" + genre + ":" + season + ":" + year;
            return hotspotCache.getOrLoad("catalog_page", pageKey, Duration.ofSeconds(30), () -> {
                Cursor c = decodeCursor(null);
                List<ExternalCatalogItem> items = rewriteCovers(catalogItems.page(sourceId, categoryId, null, status,
                        effectiveSort, genre, season, year, c.updatedAt, c.id, null, effectiveLimit));
                String next = items.size() == effectiveLimit
                        ? encodeCursor(coalesce(items.getLast().resourceUpdatedAt(), items.getLast().syncedAt()),
                                items.getLast().id())
                        : null;
                long total = hotspotCache.getOrLoad("catalog_count", countKey, Duration.ofSeconds(60),
                        () -> catalogItems.count(sourceId, categoryId, null, status, genre, season, year));
                int totalPages = total == 0 ? 1 : (int) ((total + effectiveLimit - 1) / effectiveLimit);
                return new CatalogItemPage(List.copyOf(items), next, total, 1, effectiveLimit, totalPages);
            });
        }

        long total = hasQuery
                ? catalogItems.count(sourceId, categoryId, q, status, genre, season, year)
                : hotspotCache.getOrLoad("catalog_count", countKey, Duration.ofSeconds(60),
                        () -> catalogItems.count(sourceId, categoryId, null, status, genre, season, year));
        int totalPages = total == 0 ? 1 : (int) ((total + effectiveLimit - 1) / effectiveLimit);

        if (pagedMode) {
            int currentPage = Math.max(page == null ? 1 : page, 1);
            int offset = (currentPage - 1) * effectiveLimit;
            List<ExternalCatalogItem> items = rewriteCovers(catalogItems.page(sourceId, categoryId, q, status,
                    effectiveSort, genre, season, year, null, null, offset, effectiveLimit));
            String next = currentPage < totalPages ? "page:" + (currentPage + 1) : null;
            return new CatalogItemPage(items, next, total, currentPage, effectiveLimit, totalPages);
        }

        Cursor c = decodeCursor(cursor);
        List<ExternalCatalogItem> items = rewriteCovers(catalogItems.page(sourceId, categoryId, q, status,
                effectiveSort, genre, season, year, c.updatedAt, c.id, null, effectiveLimit));
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

    private static List<ExternalCatalogItem> rewriteCovers(List<ExternalCatalogItem> items) {
        return items.stream().map(ExternalCatalogService::rewriteCover).toList();
    }

    private static ExternalCatalogItem rewriteCover(ExternalCatalogItem item) {
        if (item == null || item.coverUrl() == null || item.coverUrl().isBlank()) {
            return item;
        }
        if (item.coverUrl().startsWith("http://") || item.coverUrl().startsWith("https://")) {
            return item.withCoverUrl("/api/v1/catalog-covers/" + item.id());
        }
        return item;
    }

    // ── 目录条目详情（M11）──

    public record LineInfo(String name, int episodeCount) {}

    public record CatalogDetail(ExternalCatalogItem item, String bangumiNameCn, Double rating,
                                String summary, Integer airWeekday, List<LineInfo> lines) {}

    public CatalogDetail itemDetail(CurrentUser user, UUID itemId) {
        ExternalCatalogItem item = catalogItems.findItem(itemId).orElseThrow(
                () -> new ApiException(404, "ITEM_NOT_FOUND", "目录条目不存在。"));
        // Bangumi 元数据（若已桥接）
        record B(String cn, Double rating, String summary, Integer weekday) {}
        List<B> rows = jdbc.query(
                "SELECT name_cn, rating, summary, air_weekday FROM bangumi_subjects WHERE subject_id = ?",
                (rs, i) -> {
                    var r = rs.getBigDecimal("rating");
                    return new B(rs.getString("name_cn"), r == null ? null : r.doubleValue(),
                            rs.getString("summary"), (Integer) rs.getObject("air_weekday"));
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
        return new CatalogDetail(rewriteCover(item), b == null ? null : b.cn(), b == null ? null : b.rating(),
                b == null ? null : b.summary(), b == null ? null : b.weekday(), lines);
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

    /** 懒生成与追更：目录条目尚无剧集或资源站已更新更多集数时，按最新 play_groups 幂等补齐（保留已有集 ID 与进度）。 */
    private void ensureBrowseEpisodes(ExternalCatalogItem item) {
        List<Episode> parsed = parseEpisodes(item);
        List<ExternalEpisode> existing = externals.listEpisodesByCatalogItem(item.id());
        if (existing.size() >= parsed.size() && hasSameKeys(existing, parsed)) {
            return;
        }
        for (var ep : parsed) {
            externals.insertCatalogEpisode(UUID.randomUUID(), item.id(), item.sourceId(),
                    ep.title(), ep.url(), ep.ordinal(), ep.lineName(), ep.lineRank(),
                    ep.episodeType());
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
