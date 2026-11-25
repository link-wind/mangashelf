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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
                                String remarks, Integer episodeCount, String categoryName) {}

    /** 本季新番（含资源覆盖标记），按星期分组供前端展示；经热点缓存（TTL 60s）加速高频首页读。 */
    public List<CalendarEntry> calendar() {
        return hotspotCache.getOrLoad("calendar", "calendar", Duration.ofSeconds(60), () -> jdbc.query("""
                SELECT DISTINCT ON (b.subject_id)
                       b.subject_id, b.name, b.name_cn, b.cover_url, b.rating, b.air_weekday,
                       (c.id IS NOT NULL) AS covered, c.id AS catalog_item_id, c.work_id, b.summary,
                       c.remarks, c.episode_count, c.category_name
                FROM bangumi_subjects b
                LEFT JOIN external_catalog_items c ON c.bangumi_subject_id = b.subject_id
                WHERE b.in_calendar
                ORDER BY b.subject_id, c.episode_count DESC NULLS LAST
                """, rs -> {
            List<CalendarEntry> out = new ArrayList<>();
            while (rs.next()) {
                var rating = rs.getBigDecimal(5);
                out.add(new CalendarEntry(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rating == null ? null : rating.doubleValue(),
                        (Integer) rs.getObject(6),
                        rs.getBoolean(7), rs.getString(8), rs.getString(9), rs.getString(10),
                        rs.getString(11), (Integer) rs.getObject(12), rs.getString(13)));
            }
            out.sort(java.util.Comparator
                    .comparing((CalendarEntry e) -> e.airWeekday() == null ? 99 : e.airWeekday())
                    .thenComparing(e -> e.rating() == null ? -1 : e.rating(), java.util.Comparator.reverseOrder()));
            return List.copyOf(out);
        }));
    }

    // ── Bangumi v0 条目详情元数据补齐（tags / air_date / summary / rating）──

    public record EnrichStats(int checked, int enriched, int failed) {}

    /**
     * 通过 Bangumi v0 条目接口礼貌限速补齐缺失的 tags / air_date / summary：
     * 优先补齐本季日历条目，再补已关联目录的条目；单条失败隔离不中断整批。
     */
    public EnrichStats enrichSubjectsMetadata(int maxSubjects) {
        int limit = Math.min(Math.max(maxSubjects, 1), 120);
        List<Long> subjectIds = jdbc.query("""
                SELECT subject_id
                  FROM bangumi_subjects
                 WHERE cardinality(tags) = 0 OR air_date IS NULL OR summary = ''
                 ORDER BY in_calendar DESC, rating DESC NULLS LAST, subject_id DESC
                 LIMIT ?
                """, (rs, i) -> rs.getLong(1), limit);
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
                               synced_at = now()
                         WHERE subject_id = ?
                        """,
                        parsed.nameCn(), parsed.nameCn(),
                        parsed.summary(), parsed.summary(),
                        parsed.airDate() == null ? null : java.sql.Date.valueOf(parsed.airDate()),
                        parsed.rating(),
                        parsed.tags().toArray(new String[0]), parsed.tags().toArray(new String[0]),
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
        if (enriched > 0) {
            hotspotCache.invalidate("calendar");
            hotspotCache.invalidate("catalog_genres");
        }
        return new EnrichStats(subjectIds.size(), enriched, failed);
    }

    record EnrichedDetail(String nameCn, String summary, LocalDate airDate, Double rating, List<String> tags) {}

    /** 解析 Bangumi v0 /subjects/{id} 响应（公开包级供单元测试直接校验）。 */
    static EnrichedDetail parseV0SubjectDetail(JsonNode detail) {
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
        return new EnrichedDetail(nameCn, summary, airDate, rating, List.copyOf(tags));
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
            if (e.rating() != null && e.rating() > 0) desc.append("Bangumi ★ ").append(String.format(java.util.Locale.ROOT, "%.1f", e.rating()));
            if (e.remarks() != null && !e.remarks().isBlank()) {
                if (!desc.isEmpty()) desc.append(" · ");
                desc.append(e.remarks());
            }
            if (e.covered()) {
                if (!desc.isEmpty()) desc.append(" · ");
                desc.append("站内可播");
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
                    in_calendar = EXCLUDED.in_calendar,
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
