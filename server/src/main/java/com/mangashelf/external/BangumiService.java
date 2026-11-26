package com.mangashelf.external;

import com.mangashelf.config.HotspotCache;
import com.mangashelf.config.MangaShelfProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Bangumi 元数据源（M9）：api.bgm.tv 官方接口，礼貌限速与合规 UA。
 * 日历（本季新番）同步与标题匹配；网络受限环境可配 proxy。
 */
@Service
public class BangumiService {

    private static final Logger log = LoggerFactory.getLogger(BangumiService.class);
    private static final String UA = "linkwind/mangashelf-selfhost (personal self-hosted platform)";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final MangaShelfProperties props;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private final CatalogItemWriter itemWriter;
    private final ExternalCatalogRepository catalogItems;
    private final HotspotCache hotspotCache;
    private final CoverCacheService coverCache;
    private final ExecutorService warmupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bangumi-cover-warmup");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<UUID, CompletableFuture<OnDemandEnrichResult>> onDemandInflight =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Instant> onDemandNegative = new ConcurrentHashMap<>();

    private static final String ANILIST_GRAPHQL_URL = "https://graphql.anilist.co";
    private static final Duration ON_DEMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration ON_DEMAND_NEGATIVE_TTL = Duration.ofHours(24);
    private static final int MAX_BANNER_BYTES = 5 * 1024 * 1024;
    private static final String ANILIST_SEARCH_QUERY = """
            query ($search: String) {
              Page(page: 1, perPage: 5) {
                media(search: $search, type: ANIME, sort: SEARCH_MATCH) {
                  id
                  title { romaji english native }
                  synonyms
                  bannerImage
                  coverImage { extraLarge large }
                }
              }
            }
            """;

    @Autowired
    public BangumiService(MangaShelfProperties props, JdbcTemplate jdbc, ObjectMapper objectMapper,
                          CatalogItemWriter itemWriter, ExternalCatalogRepository catalogItems,
                          HotspotCache hotspotCache, CoverCacheService coverCache) {
        this.props = props;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.itemWriter = itemWriter;
        this.catalogItems = catalogItems;
        this.hotspotCache = hotspotCache;
        this.coverCache = coverCache;
        var cfg = props.bangumi();
        var builder = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (cfg != null && cfg.proxy() != null && !cfg.proxy().isBlank()) {
            var uri = resolveProxyUri(cfg.proxy());
            builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(),
                    uri.getPort() == -1 ? 80 : uri.getPort())));
            log.info("Bangumi HTTP via proxy {}", uri);
        }
        this.httpClient = builder.build();
    }

    /** 兼容既有测试构造器。 */
    BangumiService(MangaShelfProperties props, JdbcTemplate jdbc, ObjectMapper objectMapper,
                   CatalogItemWriter itemWriter, ExternalCatalogRepository catalogItems,
                   HotspotCache hotspotCache) {
        this(props, jdbc, objectMapper, itemWriter, catalogItems, hotspotCache, null);
    }

    /** 容器内若配置了宿主机回环代理（127.0.0.1/localhost），自动改写为 host.docker.internal 防止必挂。 */
    static URI resolveProxyUri(String rawProxy) {
        URI uri = URI.create(rawProxy.trim());
        String host = uri.getHost();
        if (host != null && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
                && Files.exists(Path.of("/.dockerenv"))) {
            URI rewritten = URI.create(rawProxy.trim().replaceFirst("(?i)127\\.0\\.0\\.1|localhost",
                    "host.docker.internal"));
            log.warn("Bangumi proxy {} points to loopback inside Docker container; rewriting to {}",
                    uri, rewritten);
            return rewritten;
        }
        return uri;
    }

    // ── 日历（本季新番）──

    public record Subject(long subjectId, String name, String nameCn, String coverUrl,
                          Double rating, LocalDate airDate, Integer airWeekday, String summary,
                          List<String> tags) {}

    /** 拉取本季日历并 upsert 缓存；返回条数。 */
    public int syncCalendar() throws Exception {
        JsonNode weeks = get("https://api.bgm.tv/calendar");
        int n = 0;
        for (JsonNode week : weeks) {
            int weekday = week.path("weekday").path("id").asInt();
            for (JsonNode it : week.path("items")) {
                List<String> tags = new ArrayList<>();
                it.path("tags").forEach(t -> {
                    String tag = t.path("name").asText("").trim();
                    if (!tag.isBlank()) tags.add(tag);
                });
                upsertSubject(new Subject(
                        it.path("id").asLong(),
                        it.path("name").asText(""),
                        it.path("name_cn").asText(""),
                        pickImage(it.path("images")),
                        it.path("rating").path("score").asDouble(0) == 0 ? null : it.path("rating").path("score").asDouble(),
                        parseDate(it.path("air_date").asText("")),
                        weekday,
                        it.path("summary").asText(""),
                        tags), true);
                n++;
            }
        }
        hotspotCache.invalidate("calendar");
        warmCalendarCoversAsync();
        return n;
    }

    @Autowired(required = false)
    private org.springframework.core.env.Environment env;

    /** 仅 API 角色就绪后后台静默预热本季尚未落盘的封面（Worker 不服务 HTTP 封面，跳过预热）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReadyWarmCovers() {
        if (env != null && env.matchesProfiles("worker")) {
            return;
        }
        if (props != null && props.bangumi() != null && props.bangumi().enabled()) {
            warmCalendarCoversAsync();
        }
    }

    public void warmCalendarCoversAsync() {
        if (coverCache == null || jdbc == null) {
            return;
        }
        warmupExecutor.submit(() -> {
            try {
                List<Long> subjectIds = jdbc.query(
                        "SELECT subject_id FROM bangumi_subjects WHERE in_calendar AND cover_url <> '' ORDER BY rating DESC NULLS LAST",
                        (rs, i) -> rs.getLong(1));
                if (subjectIds.isEmpty()) {
                    return;
                }
                int toWarm = 0;
                Semaphore permits = new Semaphore(4);
                try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (int idx = 0; idx < subjectIds.size(); idx++) {
                        long sid = subjectIds.get(idx);
                        boolean needLarge = !coverCache.isCached("bangumi", sid + "-large");
                        if (!needLarge) {
                            continue;
                        }
                        toWarm++;
                        pool.submit(() -> {
                            try {
                                permits.acquire();
                                try {
                                    fetchCover(sid, "large");
                                } finally {
                                    permits.release();
                                }
                            } catch (Exception ignored) {
                                // 单张预热失败不影响其他封面
                            }
                        });
                    }
                }
                if (toWarm > 0) {
                    log.info("bangumi cover warmup finished ({} subjects checked)", toWarm);
                }
                List<Long> bannerIds = jdbc.query(
                        "SELECT subject_id FROM bangumi_subjects WHERE in_calendar AND banner_url <> '' ORDER BY rating DESC NULLS LAST",
                        (rs, i) -> rs.getLong(1));
                for (long sid : bannerIds) {
                    if (!coverCache.isCached("bangumi-banner", sid + "-banner")) {
                        try {
                            fetchBanner(sid);
                        } catch (Exception ignored) {
                            // 单张横幅预热失败不影响其他条目
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("bangumi cover warmup skipped: {}", e.getMessage());
            }
        });
    }

    /** v0 搜索（type=2 动画）；供后续按需匹配扩展使用。 */
    public List<Subject> searchAnime(String keyword) throws Exception {
        var body = objectMapper.createObjectNode();
        body.put("keyword", keyword);
        body.set("filter", objectMapper.createObjectNode().set("type", objectMapper.createArrayNode().add(2)));
        HttpRequest request = requestBuilder("https://api.bgm.tv/v0/search/subjects?limit=5")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Bangumi 搜索 HTTP " + resp.statusCode());
        }
        List<Subject> out = new ArrayList<>();
        for (JsonNode x : objectMapper.readTree(resp.body()).path("data")) {
            List<String> tags = new ArrayList<>();
            x.path("tags").forEach(t -> {
                String tag = t.path("name").asText("").trim();
                if (!tag.isBlank()) tags.add(tag);
            });
            out.add(new Subject(x.path("id").asLong(), x.path("name").asText(""),
                    x.path("name_cn").asText(""), pickImage(x.path("images")), null,
                    parseDate(x.path("date").asText("")), null, "", tags));
        }
        return out;
    }

    /** 封面代理：按 subjectId 取图（不接受任意 URL，避免 SSRF；仅访问库内 cover_url）。 */
    public record Cover(byte[] bytes, String contentType) {}

    public Cover fetchCover(long subjectId, String size) throws Exception {
        String sz = normalizeCoverSize(size);
        if (coverCache != null) {
            CoverCacheService.CoverData data = coverCache.getOrFetch(
                    "bangumi", subjectId + "-" + sz, () -> fetchCoverRemoteQuietly(subjectId, sz));
            return data == null ? null : new Cover(data.bytes(), data.contentType());
        }
        return fetchCoverRemote(subjectId, sz);
    }

    /** 横幅代理：按 subjectId 取高清宽幅 Banner（仅读库内白名单域名 banner_url，防 SSRF）。 */
    public Cover fetchBanner(long subjectId) throws Exception {
        if (coverCache != null) {
            CoverCacheService.CoverData data = coverCache.getOrFetch(
                    "bangumi-banner", subjectId + "-banner", () -> fetchBannerRemoteQuietly(subjectId));
            return data == null ? null : new Cover(data.bytes(), data.contentType());
        }
        return fetchBannerRemote(subjectId);
    }

    private CoverCacheService.CoverData fetchBannerRemoteQuietly(long subjectId) {
        try {
            Cover c = fetchBannerRemote(subjectId);
            return c == null ? null : new CoverCacheService.CoverData(c.bytes(), c.contentType());
        } catch (Exception e) {
            log.warn("bangumi banner fetch failed for {}: {}", subjectId, e.getMessage());
            return null;
        }
    }

    private Cover fetchBannerRemote(long subjectId) throws Exception {
        List<String> urls = jdbc.query(
                "SELECT banner_url FROM bangumi_subjects WHERE subject_id = ? AND banner_url <> ''",
                (rs, i) -> rs.getString(1), subjectId);
        if (urls.isEmpty()) return null;
        String url = urls.get(0).trim();
        if (!isAllowedMediaImageUrl(url) || !CoverCacheService.isSafePublicHttpUri(URI.create(url))) {
            log.warn("rejected non-allowlisted banner url for subject {}", subjectId);
            return null;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", UA)
                .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                .GET().build();
        Exception lastErr = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpResponse<byte[]> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() != 200) return null;
                String ct = resp.headers().firstValue("content-type").orElse("image/jpeg")
                        .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                byte[] body = resp.body();
                if (!ct.startsWith("image/") || body == null || body.length == 0 || body.length > MAX_BANNER_BYTES) {
                    return null;
                }
                return new Cover(body, ct);
            } catch (java.io.IOException e) {
                lastErr = e;
                if (attempt < 2) {
                    Thread.sleep(200);
                }
            }
        }
        throw lastErr != null ? lastErr : new java.io.IOException("Bangumi banner fetch failed");
    }

    /** 仅允许 AniList CDN 与 Bangumi 图床的 HTTPS 公开地址，杜绝 SSRF。 */
    static boolean isAllowedMediaImageUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return false;
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("s4.anilist.co")
                || host.endsWith(".anilist.co")
                || host.equals("lain.bgm.tv");
    }

    private static String normalizeCoverSize(String size) {
        if (size == null || size.isBlank()) {
            return "large";
        }
        return switch (size) {
            case "large", "common", "medium", "small" -> size;
            default -> "large";
        };
    }

    private CoverCacheService.CoverData fetchCoverRemoteQuietly(long subjectId, String size) {
        try {
            Cover c = fetchCoverRemote(subjectId, size);
            return c == null ? null : new CoverCacheService.CoverData(c.bytes(), c.contentType());
        } catch (Exception e) {
            log.warn("bangumi cover fetch failed for {}-{}: {}", subjectId, size, e.getMessage());
            return null;
        }
    }

    private Cover fetchCoverRemote(long subjectId, String size) throws Exception {
        List<String> urls = jdbc.query(
                "SELECT cover_url FROM bangumi_subjects WHERE subject_id = ? AND cover_url <> ''",
                (rs, i) -> rs.getString(1), subjectId);
        if (urls.isEmpty()) return null;
        String url = urls.get(0);
        if (url.startsWith("http://lain.bgm.tv")) {
            url = "https://" + url.substring("http://".length());
        }
        // 同图多尺寸：/pic/cover/{l|m|c|s}/… —— 网格用 common（300px 级）保证 retina 清晰度
        String seg = switch (size == null ? "common" : size) {
            case "large" -> "l";
            case "common" -> "c";
            case "small" -> "s";
            default -> "m";
        };
        url = url.replaceFirst("/pic/cover/[lmcs]/", "/pic/cover/" + seg + "/");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", UA)
                .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                .GET().build();
        Exception lastErr = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpResponse<byte[]> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() != 200) return null;
                String ct = resp.headers().firstValue("content-type").orElse("image/jpeg");
                return new Cover(resp.body(), ct);
            } catch (java.io.IOException e) {
                lastErr = e;
                if (attempt < 2) {
                    Thread.sleep(200);
                }
            }
        }
        throw lastErr != null ? lastErr : new java.io.IOException("Bangumi cover fetch failed");
    }

    // ── 标题匹配（本地，Bangumi subject ↔ 资源目录条目）──

    /**
     * 用缓存的本季 subject 名称/中文名匹配目录条目；返回关联条目数。
     * 归一化（大小写/标点/空白/季期后缀/括号注释）后：先精确相等，再二元组相似度 ≥ 0.62。
     * 目录侧一次性载入内存（数千条）逐条比较，避免 N 次全表查询。
     */
    public record MatchStats(int local, int viaSearch) {
        public int total() { return local + viaSearch; }
    }

    public MatchStats matchCalendarToCatalog(ExternalSource source) {
        record S(long id, String name, String cn) {}
        record C(java.util.UUID id, String raw, String norm) {}

        List<S> subjects = new ArrayList<>();
        jdbc.query("SELECT subject_id, name, name_cn FROM bangumi_subjects WHERE in_calendar", rs -> {
            subjects.add(new S(rs.getLong(1), rs.getString(2), rs.getString(3)));
        });

        // 重置旧关联（M9 初版的前缀匹配可能过宽，按新算法重算）
        jdbc.update("UPDATE external_catalog_items SET bangumi_subject_id = NULL");

        List<C> candidates = new ArrayList<>();
        jdbc.query("SELECT id, name FROM external_catalog_items", rs -> {
            String raw = rs.getString("name");
            String norm = normalizeTitle(raw);
            if (norm.length() >= 2) {
                candidates.add(new C(rs.getObject("id", java.util.UUID.class), raw, norm));
            }
        });

        java.util.Set<java.util.UUID> used = new java.util.HashSet<>();
        List<Object[]> updates = new ArrayList<>();
        for (S s : subjects) {
            C best = null;
            double bestScore = 0;
            for (String raw : List.of(s.cn(), s.name())) {
                String norm = normalizeTitle(raw);
                if (norm.length() < 2) continue;
                for (C c : candidates) {
                    if (used.contains(c.id())) continue;
                    double score;
                    if (c.norm().equals(norm)) {
                        score = 1.0;
                    } else if (Math.min(c.norm().length(), norm.length()) >= 4) {
                        score = bigramSimilarity(c.norm(), norm);
                    } else {
                        continue;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        best = c;
                    }
                    if (bestScore >= 1.0) break;
                }
                if (bestScore >= 1.0) break;
            }
            if (best != null && bestScore >= 0.62) {
                used.add(best.id());
                updates.add(new Object[]{s.id(), best.id()});
            }
        }
        if (!updates.isEmpty()) {
            jdbc.batchUpdate(
                    "UPDATE external_catalog_items SET bangumi_subject_id = ? WHERE id = ?", updates);
        }

        // 本地未命中的，用资源站搜索反查（标题差异大的同义名）
        // 预算 120s：资源站搜索偶有慢响应，超预算即停止，剩余留给下次同步
        int viaSearch = 0;
        if (source != null) {
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(120).toNanos();
            java.util.Set<Long> linked = new java.util.HashSet<>();
            for (Object[] u : updates) linked.add((Long) u[0]);
            for (S s : subjects) {
                if (linked.contains(s.id())) continue;
                if (System.nanoTime() > deadline) {
                    log.info("bangumi reverse search budget exhausted after {} links", viaSearch);
                    break;
                }
                try {
                    if (attachViaSearch(source, s.id(), s.cn(), s.name())) viaSearch++;
                } catch (Exception e) {
                    log.warn("bangumi reverse search failed for {}: {}", s.id(), e.getMessage());
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        hotspotCache.invalidate("calendar");
        return new MatchStats(updates.size(), viaSearch);
    }

    /** 资源站搜索首批结果，名称相似度达阈值则入库并关联；返回是否关联成功。 */
    private boolean attachViaSearch(ExternalSource source, long subjectId, String cn, String name)
            throws Exception {
        for (String keyword : List.of(cn, name)) {
            if (keyword == null || keyword.isBlank()) continue;
            String url = source.baseUrl() + "/api.php/provide/vod/?ac=videolist&wd="
                    + java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(8))
                    .header("User-Agent", UA).GET().build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) continue;
            JsonNode d = objectMapper.readTree(resp.body());
            if (d.path("code").asInt(0) != 1) continue;
            String normTarget = normalizeTitle(keyword);
            for (JsonNode v : d.path("list")) {
                String candidate = v.path("vod_name").asText("");
                String normCand = normalizeTitle(candidate);
                boolean similar = normCand.equals(normTarget)
                        || (Math.min(normCand.length(), normTarget.length()) >= 4
                            && bigramSimilarity(normCand, normTarget) >= 0.5)
                        || (normCand.length() >= 4 && (normCand.contains(normTarget) || normTarget.contains(normCand)));
                if (!similar) continue;
                itemWriter.upsertVod(source.id(), v);
                var id = catalogItems.findIdByVod(source.id(), v.path("vod_id").asLong());
                if (id.isPresent()) {
                    catalogItems.linkSubject(id.get(), subjectId);
                    return true;
                }
            }
        }
        return false;
    }

    /** 标题归一化：小写、去括号注释、去标点空白、去季/期/season 后缀（两侧同规则）。 */
    static String normalizeTitle(String s) {
        if (s == null) return "";
        String v = s.toLowerCase(java.util.Locale.ROOT);
        v = v.replaceAll("（[^）]*）|\\([^)]*\\)|\\[[^]]*]|【[^】]*】", "");
        v = v.replaceAll("[\\s\\p{P}]", "");
        v = v.replaceAll("第[一二三四五六七八九十百0-9]+[季期部篇话集]", "");
        v = v.replaceAll("season[0-9]+", "");
        v = v.replaceAll("(tv|ova|oad|剧场版|完结|全集)$", "");
        return v;
    }

    /** 字符二元组 Jaccard 相似度（对中文短标题稳定，无需分词）。 */
    static double bigramSimilarity(String a, String b) {
        var sa = bigrams(a);
        var sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0;
        var inter = new java.util.HashSet<>(sa);
        inter.retainAll(sb);
        var union = new java.util.HashSet<>(sa);
        union.addAll(sb);
        return inter.size() / (double) union.size();
    }

    private static java.util.Set<String> bigrams(String s) {
        var out = new java.util.HashSet<String>();
        for (int i = 0; i + 1 < s.length(); i++) {
            out.add(s.substring(i, i + 2));
        }
        return out;
    }

    public record CalendarEntry(long subjectId, String name, String nameCn, String coverUrl,
                                Double rating, Integer airWeekday, boolean covered,
                                String catalogItemId, String workId, String summary,
                                String remarks, Integer episodeCount, String categoryName,
                                LocalDate airDate, String airTime, String studio, String mediaType,
                                String bannerUrl) {
        public CalendarEntry(long subjectId, String name, String nameCn, String coverUrl,
                             Double rating, Integer airWeekday, boolean covered,
                             String catalogItemId, String workId, String summary,
                             String remarks, Integer episodeCount, String categoryName,
                             LocalDate airDate, String airTime, String studio, String mediaType) {
            this(subjectId, name, nameCn, coverUrl, rating, airWeekday, covered,
                    catalogItemId, workId, summary, remarks, episodeCount, categoryName,
                    airDate, airTime, studio, mediaType, null);
        }
    }

    public record SeasonOption(String code, String label) {}

    /** 本季新番（默认 current），兼容既有调用。 */
    public List<CalendarEntry> calendar() {
        return calendar("current");
    }

    /** 时刻表公共过滤条件：仅保留有明确播映时间、且非儿童向/动态漫画的正常番剧。 */
    private static final String SCHEDULE_CONTENT_FILTER = """
            AND COALESCE(b.air_time, '') <> ''
            AND COALESCE(b.media_type, '') <> '动态漫画'
            AND NOT (b.tags && ARRAY['子供向', '儿童', '幼儿', '少儿', '幼教', '早教', '低幼', '动态漫画', '动态漫'])
            AND NOT (COALESCE(NULLIF(b.name_cn, ''), b.name) ~ '(屁屁侦探|熊出没|喜羊羊|汪汪队|小猪佩奇|巧虎|宝宝巴士|奥特曼|超级飞侠|猪猪侠|贝乐虎|面包超人|托马斯|大耳朵图图|动态漫画)')
            """;

    /**
     * 新番时刻表查询：仅展示已关联站内可播资源、且排除儿童番与动态漫画的条目。
     * 支持 season = current（默认本季）/ all（全部已定档）/ YYYYQ[1-4]（指定季度）。
     */
    public List<CalendarEntry> calendar(String season) {
        String key = (season == null || season.isBlank()) ? "current" : season.trim();
        return hotspotCache.getOrLoad("calendar", "calendar:" + key, Duration.ofSeconds(60), () -> {
            String whereClause;
            Object[] args;
            if ("all".equalsIgnoreCase(key)) {
                whereClause = "WHERE b.air_weekday IS NOT NULL";
                args = new Object[0];
            } else {
                LocalDate[] range = parseSeasonRange(key);
                if (range != null) {
                    whereClause = "WHERE b.air_date BETWEEN ? AND ? AND b.air_weekday IS NOT NULL";
                    args = new Object[]{java.sql.Date.valueOf(range[0]), java.sql.Date.valueOf(range[1])};
                } else {
                    whereClause = "WHERE b.in_calendar";
                    args = new Object[0];
                }
            }
            String sql = """
                    SELECT DISTINCT ON (b.subject_id)
                           b.subject_id, b.name, b.name_cn, b.cover_url, b.rating, b.air_weekday,
                           TRUE AS covered, c.id AS catalog_item_id, c.work_id, b.summary,
                           c.remarks, c.episode_count, c.category_name,
                           b.air_date, b.air_time, b.studio, b.media_type, b.banner_url
                    FROM bangumi_subjects b
                    JOIN external_catalog_items c ON c.bangumi_subject_id = b.subject_id
                    """ + whereClause + "\n" + SCHEDULE_CONTENT_FILTER + """
                    ORDER BY b.subject_id, c.episode_count DESC NULLS LAST
                    """;
            List<CalendarEntry> out = jdbc.query(sql, rs -> {
                List<CalendarEntry> list = new ArrayList<>();
                while (rs.next()) {
                    long sid = rs.getLong(1);
                    var rating = rs.getBigDecimal(5);
                    var airDateSql = rs.getDate(14);
                    String rawBanner = rs.getString(18);
                    String resolvedBanner = (rawBanner != null && !rawBanner.isBlank())
                            ? "/api/v1/bangumi-banners/" + sid : null;
                    list.add(new CalendarEntry(
                            sid, rs.getString(2), rs.getString(3),
                            rs.getString(4), rating == null ? null : rating.doubleValue(),
                            (Integer) rs.getObject(6),
                            rs.getBoolean(7), rs.getString(8), rs.getString(9), rs.getString(10),
                            rs.getString(11), (Integer) rs.getObject(12), rs.getString(13),
                            airDateSql == null ? null : airDateSql.toLocalDate(),
                            rs.getString(15), rs.getString(16), rs.getString(17),
                            resolvedBanner));
                }
                return list;
            }, args);
            out.sort(java.util.Comparator
                    .comparing((CalendarEntry e) -> e.airWeekday() == null ? 99 : e.airWeekday())
                    .thenComparing(e -> e.rating() == null ? -1 : e.rating(), java.util.Comparator.reverseOrder()));
            return List.copyOf(out);
        });
    }

    /** 列出库内可选季度（仅统计有可播非儿童番剧的季度）。 */
    public List<SeasonOption> calendarSeasons() {
        return hotspotCache.getOrLoad("calendar", "calendar_seasons", Duration.ofMinutes(5), () -> {
            List<SeasonOption> options = new ArrayList<>();
            options.add(new SeasonOption("current", "本季新番"));
            List<SeasonOption> quarters = jdbc.query("""
                    SELECT EXTRACT(YEAR FROM b.air_date)::int AS y,
                           CEIL(EXTRACT(MONTH FROM b.air_date) / 3.0)::int AS q
                      FROM bangumi_subjects b
                      JOIN external_catalog_items c ON c.bangumi_subject_id = b.subject_id
                     WHERE b.air_date IS NOT NULL AND b.air_weekday IS NOT NULL
                    """ + SCHEDULE_CONTENT_FILTER + """
                     GROUP BY y, q
                     ORDER BY y DESC, q DESC
                     LIMIT 12
                    """, (rs, i) -> {
                int y = rs.getInt(1);
                int q = rs.getInt(2);
                String name = switch (q) {
                    case 1 -> "冬季新番";
                    case 2 -> "春季新番";
                    case 3 -> "夏季新番";
                    default -> "秋季新番";
                };
                return new SeasonOption(y + "Q" + q, y + "年 " + name);
            });
            options.addAll(quarters);
            if (quarters.size() > 1) {
                options.add(new SeasonOption("all", "全部季度"));
            }
            return List.copyOf(options);
        });
    }

    static LocalDate[] parseSeasonRange(String season) {
        if (season == null) return null;
        var m = java.util.regex.Pattern.compile("^(\\d{4})Q([1-4])$").matcher(season.trim().toUpperCase(java.util.Locale.ROOT));
        if (!m.matches()) return null;
        int year = Integer.parseInt(m.group(1));
        int q = Integer.parseInt(m.group(2));
        int startMonth = (q - 1) * 3 + 1;
        LocalDate start = LocalDate.of(year, startMonth, 1);
        LocalDate end = start.plusMonths(3).minusDays(1);
        return new LocalDate[]{start, end};
    }

    // ── Bangumi v0 条目详情元数据补齐（tags / air_date / air_time / studio / media_type / summary / rating）──

    public record EnrichStats(int checked, int enriched, int failed) {}

    public EnrichStats enrichSubjectsMetadata(int maxSubjects) {
        return enrichSubjectsMetadata(maxSubjects, false);
    }

    /**
     * 通过 Bangumi v0 条目接口礼貌限速补齐缺失的元数据：
     * 优先补齐本季日历条目，再补已关联目录的条目；单条失败隔离不中断整批。
     * force=true 时对本季条目执行全量刷新（用于补齐新增字段如 air_time/studio/media_type）。
     */
    public EnrichStats enrichSubjectsMetadata(int maxSubjects, boolean force) {
        int limit = Math.min(Math.max(maxSubjects, 1), 150);
        String sql = force
                ? """
                  SELECT subject_id
                    FROM bangumi_subjects
                   ORDER BY in_calendar DESC, rating DESC NULLS LAST, subject_id DESC
                   LIMIT ?
                  """
                : """
                  SELECT subject_id
                    FROM bangumi_subjects
                   WHERE cardinality(tags) = 0 OR air_date IS NULL OR summary = '' OR media_type = ''
                   ORDER BY in_calendar DESC, rating DESC NULLS LAST, subject_id DESC
                   LIMIT ?
                  """;
        List<Long> subjectIds = jdbc.query(sql, (rs, i) -> rs.getLong(1), limit);
        int enriched = 0;
        int failed = 0;
        for (int i = 0; i < subjectIds.size(); i++) {
            long sid = subjectIds.get(i);
            try {
                JsonNode detail = get("https://api.bgm.tv/v0/subjects/" + sid);
                EnrichedDetail parsed = parseV0SubjectDetail(detail);
                jdbc.update("""
                        UPDATE bangumi_subjects
                           SET name_cn = CASE WHEN ? <> '' THEN ? ELSE name_cn END,
                               summary = CASE WHEN ? <> '' THEN ? ELSE summary END,
                               air_date = COALESCE(?, air_date),
                               rating = COALESCE(?, rating),
                               tags = CASE WHEN cardinality(?) > 0 THEN ? ELSE tags END,
                               air_time = COALESCE(?, air_time),
                               studio = CASE WHEN ? <> '' THEN ? ELSE studio END,
                               media_type = CASE WHEN ? <> '' THEN ? ELSE media_type END,
                               synced_at = now()
                         WHERE subject_id = ?
                        """,
                        parsed.nameCn(), parsed.nameCn(),
                        parsed.summary(), parsed.summary(),
                        parsed.airDate() == null ? null : java.sql.Date.valueOf(parsed.airDate()),
                        parsed.rating(),
                        parsed.tags().toArray(new String[0]), parsed.tags().toArray(new String[0]),
                        parsed.airTime(),
                        parsed.studio(), parsed.studio(),
                        parsed.mediaType(), parsed.mediaType(),
                        sid);
                enriched++;
            } catch (Exception e) {
                failed++;
                log.warn("bangumi v0 enrich failed for {}: {}", sid, e.getMessage());
            }
            if (i + 1 < subjectIds.size()) {
                try {
                    Thread.sleep(220);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        int timedUpdated = syncAirTimesFromBangumiDataQuietly();
        if (enriched > 0 || timedUpdated > 0) {
            hotspotCache.invalidate("calendar");
            hotspotCache.invalidate("catalog_genres");
        }
        return new EnrichStats(subjectIds.size(), enriched, failed);
    }

    private static final String[] BANGUMI_DATA_URLS = {
            "https://cdn.jsdelivr.net/npm/bangumi-data@0.3/dist/data.json",
            "https://unpkg.com/bangumi-data@0.3/dist/data.json"
    };
    private static final java.time.ZoneId SHANGHAI = java.time.ZoneId.of("Asia/Shanghai");
    private static final java.time.format.DateTimeFormatter HHMM =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 从开源社区数据集 bangumi-data（MIT）一次拉取精确首播 UTC 时间戳，
     * 换算为北京时间 HH:mm 批量回填 bangumi_subjects.air_time。
     */
    public int syncAirTimesFromBangumiDataQuietly() {
        try {
            return syncAirTimesFromBangumiData();
        } catch (Exception e) {
            log.warn("bangumi-data air_time sync skipped: {}", e.getMessage());
            return 0;
        }
    }

    public int syncAirTimesFromBangumiData() throws Exception {
        List<Long> existingIds = jdbc.query(
                "SELECT subject_id FROM bangumi_subjects", (rs, i) -> rs.getLong(1));
        if (existingIds.isEmpty()) return 0;
        java.util.Set<Long> idSet = new java.util.HashSet<>(existingIds);

        JsonNode root = null;
        Exception lastErr = null;
        for (String url : BANGUMI_DATA_URLS) {
            try {
                root = get(url);
                break;
            } catch (Exception e) {
                lastErr = e;
            }
        }
        if (root == null) {
            throw lastErr != null ? lastErr : new IllegalStateException("bangumi-data fetch failed");
        }

        java.util.Map<Long, String> times = parseBangumiDataAirTimes(root);
        List<Object[]> batch = new ArrayList<>();
        for (var entry : times.entrySet()) {
            if (idSet.contains(entry.getKey())) {
                batch.add(new Object[]{entry.getValue(), entry.getKey()});
            }
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate("UPDATE bangumi_subjects SET air_time = ? WHERE subject_id = ?", batch);
            hotspotCache.invalidate("calendar");
            log.info("bangumi-data air_time synced for {}/{} subjects", batch.size(), existingIds.size());
        }
        return batch.size();
    }

    /** 解析 bangumi-data data.json → { bangumiSubjectId: "HH:mm" (Asia/Shanghai) }。 */
    static java.util.Map<Long, String> parseBangumiDataAirTimes(JsonNode root) {
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        JsonNode items = root.path("items");
        if (!items.isArray()) return out;
        for (JsonNode item : items) {
            String begin = item.path("begin").asText("").trim();
            if (begin.isBlank()) continue;
            String hhmm;
            try {
                hhmm = java.time.Instant.parse(begin).atZone(SHANGHAI).format(HHMM);
            } catch (Exception ignored) {
                continue;
            }
            JsonNode sites = item.path("sites");
            if (!sites.isArray()) continue;
            for (JsonNode site : sites) {
                if (!"bangumi".equals(site.path("site").asText(""))) continue;
                String rawId = site.path("id").asText("").trim();
                try {
                    long sid = Long.parseLong(rawId);
                    out.put(sid, hhmm);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    record EnrichedDetail(String nameCn, String summary, LocalDate airDate, Double rating, List<String> tags,
                          String airTime, String studio, String mediaType) {}

    private static final java.util.regex.Pattern TIME_PATTERN =
            java.util.regex.Pattern.compile("(?<!\\d)(\\d{1,2})[:：](\\d{2})(?!\\d)");
    private static final java.util.regex.Pattern JAPANESE_KANA =
            java.util.regex.Pattern.compile("[\\u3040-\\u30FF]");

    /** 解析 Bangumi v0 /subjects/{id} 响应（公开包级供单元测试直接校验）。 */
    static EnrichedDetail parseV0SubjectDetail(JsonNode detail) {
        String name = detail.path("name").asText("").trim();
        String nameCn = detail.path("name_cn").asText("").trim();
        String summary = detail.path("summary").asText("").trim();
        LocalDate airDate = parseDate(detail.path("date").asText(""));
        Double rating = detail.path("rating").path("score").isNumber()
                ? detail.path("rating").path("score").asDouble() : null;
        if (rating != null && rating <= 0) rating = null;

        java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>();
        detail.path("meta_tags").forEach(t -> {
            String v = t.asText("").trim();
            if (!v.isBlank()) tags.add(v);
        });
        List<JsonNode> rawTags = new ArrayList<>();
        detail.path("tags").forEach(rawTags::add);
        rawTags.stream()
                .sorted((a, b) -> Integer.compare(b.path("count").asInt(0), a.path("count").asInt(0)))
                .limit(12)
                .forEach(t -> {
                    String v = t.path("name").asText("").trim();
                    if (!v.isBlank()) tags.add(v);
                });

        String mediaType = detail.path("platform").asText("").trim();
        if (mediaType.length() > 24) mediaType = mediaType.substring(0, 24);

        boolean isLikelyChinese = tags.contains("国产") || tags.contains("中国") || tags.contains("国漫");
        boolean isLikelyJapanese = !isLikelyChinese && (JAPANESE_KANA.matcher(name).find() || tags.contains("日本"));

        String studio = extractStudio(detail.path("infobox"));
        String airTime = extractAirTime(detail.path("infobox"), isLikelyJapanese);

        return new EnrichedDetail(nameCn, summary, airDate, rating, List.copyOf(tags), airTime, studio, mediaType);
    }

    private static String extractStudio(JsonNode infobox) {
        if (!infobox.isArray()) return "";
        String fallback = "";
        for (JsonNode item : infobox) {
            String key = item.path("key").asText("").trim();
            if ("动画制作".equals(key)) {
                String v = formatInfoboxValue(item.path("value"), 2);
                if (!v.isBlank()) return truncate(v, 120);
            } else if (fallback.isBlank() && ("制作".equals(key) || "出品".equals(key))) {
                String v = formatInfoboxValue(item.path("value"), 2);
                if (!v.isBlank()) fallback = truncate(v, 120);
            }
        }
        return fallback;
    }

    private static String extractAirTime(JsonNode infobox, boolean isLikelyJapanese) {
        if (!infobox.isArray()) return null;
        String[] priorityKeys = {"放送时间", "播放时间", "播出时间", "放送星期", "放送开始"};
        for (String targetKey : priorityKeys) {
            for (JsonNode item : infobox) {
                if (!targetKey.equals(item.path("key").asText("").trim())) continue;
                String parsed = parseInfoboxTime(item.path("value"), isLikelyJapanese);
                if (parsed != null) return parsed;
            }
        }
        return null;
    }

    private static String parseInfoboxTime(JsonNode valueNode, boolean isLikelyJapanese) {
        if (valueNode.isString()) {
            return parseTimeFromText(valueNode.asText(""), isLikelyJapanese);
        }
        if (valueNode.isArray()) {
            // 优先匹配明确标注为中国/北京时间的条目
            for (JsonNode entry : valueNode) {
                String k = entry.path("k").asText("");
                String v = entry.path("v").asText("");
                if (containsChinaMarker(k) || containsChinaMarker(v)) {
                    String t = parseTimeFromText(v, false);
                    if (t != null) return t;
                }
            }
            for (JsonNode entry : valueNode) {
                String k = entry.path("k").asText("");
                String v = entry.path("v").asText("");
                boolean jst = containsJapanMarker(k) || containsJapanMarker(v) || isLikelyJapanese;
                String t = parseTimeFromText(v, jst);
                if (t != null) return t;
            }
        }
        return null;
    }

    static String parseTimeFromText(String raw, boolean assumeJst) {
        if (raw == null || raw.isBlank()) return null;
        var m = TIME_PATTERN.matcher(raw);
        if (!m.find()) return null;
        int hour = Integer.parseInt(m.group(1));
        int minute = Integer.parseInt(m.group(2));
        if (minute < 0 || minute > 59 || hour < 0 || hour > 30) return null;

        boolean convertJst;
        if (containsChinaMarker(raw)) {
            convertJst = false;
        } else if (hour >= 24 || containsJapanMarker(raw)) {
            convertJst = true;
        } else {
            convertJst = assumeJst;
        }
        int totalMin = hour * 60 + minute;
        if (convertJst) {
            totalMin -= 60;
        }
        totalMin = ((totalMin % 1440) + 1440) % 1440;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", totalMin / 60, totalMin % 60);
    }

    private static boolean containsChinaMarker(String s) {
        if (s == null || s.isBlank()) return false;
        return s.contains("中国") || s.contains("北京") || s.contains("CST") || s.contains("UTC+8")
                || s.contains("哔哩") || s.contains("B站") || s.contains("腾讯") || s.contains("优酷") || s.contains("爱奇艺");
    }

    private static boolean containsJapanMarker(String s) {
        if (s == null || s.isBlank()) return false;
        return s.contains("日本") || s.contains("JST") || s.contains("UTC+9") || s.contains("TOKYO")
                || s.contains("東京") || s.contains("东京") || s.contains("MX") || s.contains("TBS");
    }

    private static String formatInfoboxValue(JsonNode valueNode, int maxItems) {
        if (valueNode.isString()) return valueNode.asText("").trim();
        if (valueNode.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode entry : valueNode) {
                String v = entry.path("v").asText("").trim();
                if (!v.isBlank()) {
                    parts.add(v);
                    if (parts.size() >= maxItems) break;
                }
            }
            return String.join(" / ", parts);
        }
        return "";
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    // ── 追番日历 .ics 导出（RFC 5545）──

    private static final String[] ICS_BYDAY = {"", "MO", "TU", "WE", "TH", "FR", "SA", "SU"};

    /** 生成标准 RFC 5545 周循环 iCalendar 日历文本（支持全本季或个人追番子集）。 */
    public static String buildCalendarIcs(List<CalendarEntry> entries, String calendarName) {
        LocalDate today = LocalDate.now();
        String stamp = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//MangaShelf//Anime Schedule//ZH\r\n");
        sb.append("CALSCALE:GREGORIAN\r\n");
        sb.append("METHOD:PUBLISH\r\n");
        sb.append("X-WR-CALNAME:").append(escapeIcs(calendarName == null ? "MangaShelf 新番时刻表" : calendarName)).append("\r\n");
        sb.append("X-WR-TIMEZONE:Asia/Shanghai\r\n");

        for (CalendarEntry e : entries) {
            Integer dow = e.airWeekday();
            if (dow == null || dow < 1 || dow > 7) continue;
            LocalDate firstDate = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.of(dow)));
            String dateStr = firstDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            String title = (e.nameCn() != null && !e.nameCn().isBlank()) ? e.nameCn() : e.name();
            StringBuilder desc = new StringBuilder();
            if (e.airTime() != null && !e.airTime().isBlank()) desc.append(e.airTime());
            if (e.rating() != null && e.rating() > 0) {
                if (!desc.isEmpty()) desc.append(" · ");
                desc.append("Bangumi ★ ").append(String.format(java.util.Locale.ROOT, "%.1f", e.rating()));
            }
            if (e.remarks() != null && !e.remarks().isBlank()) {
                if (!desc.isEmpty()) desc.append(" · ");
                desc.append(e.remarks());
            }

            sb.append("BEGIN:VEVENT\r\n");
            sb.append("UID:mangashelf-bgm-").append(e.subjectId()).append("@mangashelf.local\r\n");
            sb.append("DTSTAMP:").append(stamp).append("\r\n");
            sb.append("DTSTART;VALUE=DATE:").append(dateStr).append("\r\n");
            sb.append("RRULE:FREQ=WEEKLY;BYDAY=").append(ICS_BYDAY[dow]).append("\r\n");
            sb.append("SUMMARY:").append(escapeIcs("【新番】" + title)).append("\r\n");
            if (!desc.isEmpty()) {
                sb.append("DESCRIPTION:").append(escapeIcs(desc.toString())).append("\r\n");
            }
            sb.append("END:VEVENT\r\n");
        }
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private static String escapeIcs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace(",", "\\,")
                .replace(";", "\\;");
    }

    // ── 高清横幅（AniList 1900×400）与详情页按需高清封面回填 ──

    public record AniListMedia(String bannerImage, String coverExtraLarge) {}

    public record OnDemandEnrichResult(Long bangumiSubjectId, String hdCoverUrl) {}

    public record HdEnrichStats(int bannersChecked, int bannersFilled,
                                int catalogChecked, int catalogLinkedBangumi, int catalogFilledHdCover) {}

    /**
     * 详情页按需高清封面与元数据补齐（单飞 + 24h 负缓存 + 短超时）：
     * 1) 先以严格标题相似度搜索 Bangumi v0，命中则拉取 v0 详情写入 bangumi_subjects 并关联，
     *    详情页即刻获得 1200×1700 级大图 + 评分 + 简介 + 标签；
     * 2) 若 Bangumi 未命中，则回退查询 AniList coverImage.extraLarge 写入 external_catalog_items.hd_cover_url
     *    并失效旧低清封面缓存。
     */
    public OnDemandEnrichResult enrichCatalogItemOnDemand(UUID itemId, String itemName) {
        if (itemId == null || itemName == null || itemName.isBlank()) {
            return null;
        }
        String trimmed = itemName.trim();
        if (trimmed.length() < 2 || trimmed.contains("动态漫")) {
            return null;
        }
        Instant negUntil = onDemandNegative.get(itemId);
        if (negUntil != null) {
            if (Instant.now().isBefore(negUntil)) {
                return null;
            }
            onDemandNegative.remove(itemId, negUntil);
        }

        CompletableFuture<OnDemandEnrichResult> created = new CompletableFuture<>();
        CompletableFuture<OnDemandEnrichResult> existing = onDemandInflight.putIfAbsent(itemId, created);
        if (existing != null) {
            return existing.join();
        }
        try {
            OnDemandEnrichResult result = performOnDemandEnrich(itemId, trimmed);
            if (result == null) {
                markOnDemandNegative(itemId);
            }
            created.complete(result);
            return result;
        } catch (Exception e) {
            log.debug("on-demand HD enrich failed for {} ({}): {}", itemId, trimmed, e.getMessage());
            markOnDemandNegative(itemId);
            created.complete(null);
            return null;
        } finally {
            onDemandInflight.remove(itemId, created);
        }
    }

    private OnDemandEnrichResult performOnDemandEnrich(UUID itemId, String itemName) {
        // 1) 优先匹配 Bangumi（可同时补齐高清 1200px+ 封面、评分、简介、标签与首播日）
        try {
            List<Subject> candidates = searchAnimeWithTimeout(itemName, ON_DEMAND_TIMEOUT);
            Subject best = pickBestBangumiSubject(itemName, candidates);
            if (best != null && best.subjectId() > 0 && best.coverUrl() != null && !best.coverUrl().isBlank()) {
                Subject toSave = best;
                EnrichedDetail v0 = null;
                try {
                    JsonNode detail = getWithTimeout("https://api.bgm.tv/v0/subjects/" + best.subjectId(), ON_DEMAND_TIMEOUT);
                    v0 = parseV0SubjectDetail(detail);
                    String detailCover = pickImage(detail.path("images"));
                    toSave = new Subject(
                            best.subjectId(),
                            detail.path("name").asText(best.name()),
                            v0.nameCn().isBlank() ? best.nameCn() : v0.nameCn(),
                            detailCover.isBlank() ? best.coverUrl() : detailCover,
                            v0.rating(),
                            v0.airDate() != null ? v0.airDate() : best.airDate(),
                            null,
                            v0.summary(),
                            v0.tags().isEmpty() ? best.tags() : v0.tags());
                } catch (Exception ignored) {
                    // v0 详情偶发超时时先保存搜索命中的基础字段与封面
                }
                upsertSubject(toSave, false);
                if (v0 != null) {
                    jdbc.update("""
                            UPDATE bangumi_subjects
                               SET air_time = COALESCE(?, air_time),
                                   studio = CASE WHEN ? <> '' THEN ? ELSE studio END,
                                   media_type = CASE WHEN ? <> '' THEN ? ELSE media_type END
                             WHERE subject_id = ?
                            """, v0.airTime(), v0.studio(), v0.studio(),
                            v0.mediaType(), v0.mediaType(), best.subjectId());
                }
                catalogItems.linkSubject(itemId, best.subjectId());
                hotspotCache.invalidate("catalog_page");
                return new OnDemandEnrichResult(best.subjectId(), null);
            }
        } catch (Exception e) {
            log.debug("bangumi on-demand search skipped for {}: {}", itemName, e.getMessage());
        }

        // 2) 回退 AniList 高清竖版封面（coverImage.extraLarge）
        try {
            AniListMedia media = queryAniListMedia(ON_DEMAND_TIMEOUT, itemName);
            if (media != null && media.coverExtraLarge() != null && !media.coverExtraLarge().isBlank()) {
                jdbc.update("UPDATE external_catalog_items SET hd_cover_url = ? WHERE id = ?",
                        media.coverExtraLarge(), itemId);
                if (coverCache != null) {
                    coverCache.evict("catalog", itemId.toString());
                }
                hotspotCache.invalidate("catalog_page");
                return new OnDemandEnrichResult(null, media.coverExtraLarge());
            }
        } catch (Exception e) {
            log.debug("anilist on-demand cover skipped for {}: {}", itemName, e.getMessage());
        }
        return null;
    }

    /**
     * 管理员批量补齐高清横幅与热门番剧高清封面：
     * 1) 为缺少 banner_url 的 bangumi_subjects（优先本季新番与高分番）拉取 AniList 1900×400 横幅并预热落盘；
     * 2) 为未关联 Bangumi 的热门目录条目按需补齐 Bangumi 关联或 AniList 高清封面。
     */
    public HdEnrichStats batchEnrichHdAssets(int bannerLimit, int catalogLimit) {
        int safeBannerLimit = Math.min(Math.max(bannerLimit, 0), 150);
        int safeCatalogLimit = Math.min(Math.max(catalogLimit, 0), 150);

        int bannersChecked = 0;
        int bannersFilled = 0;
        if (safeBannerLimit > 0) {
            record S(long id, String name, String cn) {}
            List<S> subjects = jdbc.query("""
                    SELECT subject_id, name, name_cn
                      FROM bangumi_subjects
                     WHERE banner_url = ''
                     ORDER BY in_calendar DESC, rating DESC NULLS LAST, subject_id DESC
                     LIMIT ?
                    """, (rs, i) -> new S(rs.getLong(1), rs.getString(2), rs.getString(3)), safeBannerLimit);
            for (int i = 0; i < subjects.size(); i++) {
                S s = subjects.get(i);
                bannersChecked++;
                try {
                    AniListMedia media = queryAniListMedia(Duration.ofSeconds(8), s.name(), s.cn());
                    if (media != null && media.bannerImage() != null && !media.bannerImage().isBlank()) {
                        jdbc.update("UPDATE bangumi_subjects SET banner_url = ? WHERE subject_id = ?",
                                media.bannerImage(), s.id());
                        bannersFilled++;
                        try {
                            fetchBanner(s.id());
                        } catch (Exception ignored) {
                            // 预热失败不影响入库，前台访问时会按需再拉
                        }
                    }
                } catch (Exception e) {
                    log.debug("anilist banner batch failed for {}: {}", s.id(), e.getMessage());
                }
                if (i + 1 < subjects.size()) {
                    try {
                        Thread.sleep(380);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        int catalogChecked = 0;
        int catalogLinkedBangumi = 0;
        int catalogFilledHdCover = 0;
        if (safeCatalogLimit > 0) {
            record C(UUID id, String name) {}
            List<C> items = jdbc.query("""
                    SELECT id, name
                      FROM external_catalog_items
                     WHERE bangumi_subject_id IS NULL
                       AND hd_cover_url = ''
                       AND name NOT LIKE '%动态漫%'
                       AND name NOT LIKE '%动态漫画%'
                     ORDER BY episode_count DESC, COALESCE(resource_updated_at, synced_at) DESC
                     LIMIT ?
                    """, (rs, i) -> new C(rs.getObject("id", UUID.class), rs.getString("name")), safeCatalogLimit);
            for (int i = 0; i < items.size(); i++) {
                C c = items.get(i);
                catalogChecked++;
                OnDemandEnrichResult res = enrichCatalogItemOnDemand(c.id(), c.name());
                if (res != null) {
                    if (res.bangumiSubjectId() != null) {
                        catalogLinkedBangumi++;
                    } else if (res.hdCoverUrl() != null) {
                        catalogFilledHdCover++;
                    }
                }
                if (i + 1 < items.size()) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (bannersFilled > 0 || catalogLinkedBangumi > 0 || catalogFilledHdCover > 0) {
            hotspotCache.invalidate("calendar");
            hotspotCache.invalidate("catalog_page");
            hotspotCache.invalidate("catalog_genres");
        }
        return new HdEnrichStats(bannersChecked, bannersFilled, catalogChecked,
                catalogLinkedBangumi, catalogFilledHdCover);
    }

    private List<Subject> searchAnimeWithTimeout(String keyword, Duration timeout) throws Exception {
        var body = objectMapper.createObjectNode();
        body.put("keyword", keyword);
        body.set("filter", objectMapper.createObjectNode().set("type", objectMapper.createArrayNode().add(2)));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.bgm.tv/v0/search/subjects?limit=5"))
                .timeout(timeout)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Bangumi 搜索 HTTP " + resp.statusCode());
        }
        List<Subject> out = new ArrayList<>();
        for (JsonNode x : objectMapper.readTree(resp.body()).path("data")) {
            List<String> tags = new ArrayList<>();
            x.path("tags").forEach(t -> {
                String tag = t.path("name").asText("").trim();
                if (!tag.isBlank()) tags.add(tag);
            });
            out.add(new Subject(x.path("id").asLong(), x.path("name").asText(""),
                    x.path("name_cn").asText(""), pickImage(x.path("images")), null,
                    parseDate(x.path("date").asText("")), null, "", tags));
        }
        return out;
    }

    private JsonNode getWithTimeout(String url, Duration timeout) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Bangumi HTTP " + resp.statusCode());
        }
        return objectMapper.readTree(resp.body());
    }

    /** 严格标题匹配：精确归一化相等或长度≥4且二元组相似度≥0.72；季号一致优先。 */
    static Subject pickBestBangumiSubject(String query, List<Subject> candidates) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        int querySeason = extractSeasonNumber(query);
        Subject best = null;
        double bestScore = 0;
        for (Subject c : candidates) {
            double rawScore = Math.max(titleMatchScore(query, c.nameCn()), titleMatchScore(query, c.name()));
            if (rawScore < 0.72) {
                continue;
            }
            int candSeason = Math.max(extractSeasonNumber(c.nameCn()), extractSeasonNumber(c.name()));
            double adjusted = rawScore;
            if (querySeason > 0 && candSeason == querySeason) {
                adjusted += 0.08;
            } else if (querySeason > 0 && candSeason > 0 && candSeason != querySeason) {
                adjusted -= 0.12;
            }
            if (adjusted > bestScore) {
                bestScore = adjusted;
                best = c;
            }
        }
        return best;
    }

    /** 查询 AniList 免费公开 GraphQL 接口获取宽幅 Banner 与高清竖版封面。 */
    AniListMedia queryAniListMedia(Duration timeout, String... rawKeywords) throws Exception {
        List<String> queries = new ArrayList<>();
        for (String kw : rawKeywords) {
            if (kw != null && !kw.isBlank()) {
                String t = kw.trim();
                if (!queries.contains(t)) {
                    queries.add(t);
                }
            }
        }
        if (queries.isEmpty()) {
            return null;
        }
        AniListMedia fallbackCoverOnly = null;
        for (String kw : queries) {
            var payload = objectMapper.createObjectNode();
            payload.put("query", ANILIST_SEARCH_QUERY);
            payload.set("variables", objectMapper.createObjectNode().put("search", kw));
            HttpRequest req = HttpRequest.newBuilder(URI.create(ANILIST_GRAPHQL_URL))
                    .timeout(timeout)
                    .header("User-Agent", UA)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 429) {
                log.warn("AniList rate limited (429), backing off 4s");
                Thread.sleep(4000);
                continue;
            }
            if (resp.statusCode() != 200) {
                continue;
            }
            AniListMedia hit = parseBestAniListMatch(objectMapper.readTree(resp.body()), queries);
            if (hit != null) {
                if (hit.bannerImage() != null && !hit.bannerImage().isBlank()) {
                    return hit;
                }
                if (fallbackCoverOnly == null && hit.coverExtraLarge() != null && !hit.coverExtraLarge().isBlank()) {
                    fallbackCoverOnly = hit;
                }
            }
        }
        return fallbackCoverOnly;
    }

    /** 从 AniList Page.media 中选出与查询标题严格匹配的高清图资源（公开包级供单测）。 */
    static AniListMedia parseBestAniListMatch(JsonNode root, List<String> queries) {
        JsonNode mediaList = root.path("data").path("Page").path("media");
        if (!mediaList.isArray() || queries == null || queries.isEmpty()) {
            return null;
        }
        AniListMedia best = null;
        double bestScore = 0;
        for (JsonNode m : mediaList) {
            List<String> titles = new ArrayList<>();
            JsonNode titleNode = m.path("title");
            for (String f : new String[]{"native", "romaji", "english"}) {
                String v = titleNode.path(f).asText("").trim();
                if (!v.isBlank()) titles.add(v);
            }
            m.path("synonyms").forEach(s -> {
                String v = s.asText("").trim();
                if (!v.isBlank()) titles.add(v);
            });

            double score = 0;
            for (String q : queries) {
                for (String t : titles) {
                    score = Math.max(score, titleMatchScore(q, t));
                }
            }
            if (score < 0.65) {
                continue;
            }
            String banner = m.path("bannerImage").asText("").trim();
            if (!isAllowedMediaImageUrl(banner)) {
                banner = "";
            }
            String cover = m.path("coverImage").path("extraLarge").asText("").trim();
            if (cover.isBlank()) {
                cover = m.path("coverImage").path("large").asText("").trim();
            }
            if (!isAllowedMediaImageUrl(cover)) {
                cover = "";
            }
            if (banner.isBlank() && cover.isBlank()) {
                continue;
            }
            double ranked = score + (banner.isBlank() ? 0.0 : 0.05);
            if (ranked > bestScore) {
                bestScore = ranked;
                best = new AniListMedia(banner.isBlank() ? null : banner, cover.isBlank() ? null : cover);
            }
        }
        return best;
    }

    static double titleMatchScore(String a, String b) {
        String na = normalizeTitle(a);
        String nb = normalizeTitle(b);
        if (na.length() < 2 || nb.length() < 2) {
            return 0;
        }
        if (na.equals(nb)) {
            return 1.0;
        }
        if (Math.min(na.length(), nb.length()) < 4) {
            return 0;
        }
        return bigramSimilarity(na, nb);
    }

    static int extractSeasonNumber(String title) {
        if (title == null || title.isBlank()) return 0;
        var m = java.util.regex.Pattern.compile("(?i)(?:第\\s*([一二三四五六七八九十0-9]+)\\s*[季期部]|season\\s*([0-9]+))")
                .matcher(title);
        if (!m.find()) return 0;
        String token = m.group(1) != null ? m.group(1) : m.group(2);
        return switch (token) {
            case "一", "1" -> 1;
            case "二", "2" -> 2;
            case "三", "3" -> 3;
            case "四", "4" -> 4;
            case "五", "5" -> 5;
            case "六", "6" -> 6;
            case "七", "7" -> 7;
            case "八", "8" -> 8;
            case "九", "9" -> 9;
            case "十", "10" -> 10;
            default -> {
                try {
                    yield Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    yield 0;
                }
            }
        };
    }

    private void markOnDemandNegative(UUID itemId) {
        if (onDemandNegative.size() > 4096) {
            Instant now = Instant.now();
            onDemandNegative.entrySet().removeIf(e -> !now.isBefore(e.getValue()));
            if (onDemandNegative.size() > 4096) {
                onDemandNegative.clear();
            }
        }
        onDemandNegative.put(itemId, Instant.now().plus(ON_DEMAND_NEGATIVE_TTL));
    }

    // ── 内部 ──

    /** 优先大图（Banner 需要），回退 medium/common。 */
    private static String pickImage(JsonNode images) {
        for (String field : new String[]{"large", "medium", "common"}) {
            String v = images.path(field).asText("");
            if (!v.isBlank()) return v;
        }
        return "";
    }

    private void upsertSubject(Subject s, boolean inCalendar) {
        String[] tagArr = s.tags().toArray(new String[0]);
        jdbc.update("""
                INSERT INTO bangumi_subjects
                    (subject_id, name, name_cn, cover_url, rating, air_date, air_weekday, summary,
                     in_calendar, tags)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (subject_id) DO UPDATE SET
                    name = EXCLUDED.name,
                    name_cn = CASE WHEN EXCLUDED.name_cn <> '' THEN EXCLUDED.name_cn ELSE bangumi_subjects.name_cn END,
                    cover_url = CASE WHEN EXCLUDED.cover_url <> '' THEN EXCLUDED.cover_url ELSE bangumi_subjects.cover_url END,
                    rating = COALESCE(EXCLUDED.rating, bangumi_subjects.rating),
                    air_date = COALESCE(EXCLUDED.air_date, bangumi_subjects.air_date),
                    air_weekday = COALESCE(EXCLUDED.air_weekday, bangumi_subjects.air_weekday),
                    summary = CASE WHEN EXCLUDED.summary <> '' THEN EXCLUDED.summary ELSE bangumi_subjects.summary END,
                    in_calendar = (bangumi_subjects.in_calendar OR EXCLUDED.in_calendar),
                    tags = CASE WHEN cardinality(EXCLUDED.tags) > 0 THEN EXCLUDED.tags ELSE bangumi_subjects.tags END,
                    synced_at = now()
                """, s.subjectId(), s.name(), s.nameCn(), s.coverUrl(), s.rating(), s.airDate(),
                s.airWeekday(), s.summary(), inCalendar, tagArr);
    }

    private HttpRequest.Builder requestBuilder(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("User-Agent", UA).header("Accept", "application/json");
    }

    /** GET + 一次重试：代理链路偶发连接超时（实测），重试显著提高同步成功率。 */
    private JsonNode get(String url) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpResponse<String> resp = httpClient.send(requestBuilder(url).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new IllegalStateException("Bangumi HTTP " + resp.statusCode());
                }
                return objectMapper.readTree(resp.body());
            } catch (java.io.IOException e) {
                last = e;
                log.warn("bangumi request attempt {} failed: {}", attempt, e.getMessage());
                Thread.sleep(1000);
            }
        }
        throw last != null ? last : new IllegalStateException("Bangumi request failed");
    }

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }
}
