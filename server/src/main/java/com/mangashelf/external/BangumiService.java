package com.mangashelf.external;

import com.mangashelf.config.MangaShelfProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    public BangumiService(MangaShelfProperties props, JdbcTemplate jdbc, ObjectMapper objectMapper,
                          CatalogItemWriter itemWriter, ExternalCatalogRepository catalogItems) {
        this.props = props;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.itemWriter = itemWriter;
        this.catalogItems = catalogItems;
        var cfg = props.bangumi();
        var builder = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (cfg != null && cfg.proxy() != null && !cfg.proxy().isBlank()) {
            var uri = URI.create(cfg.proxy());
            builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(),
                    uri.getPort() == -1 ? 80 : uri.getPort())));
            log.info("Bangumi HTTP via proxy {}", uri);
        }
        this.httpClient = builder.build();
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
        return n;
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

    /** 封面磁盘缓存目录（首次回源后落盘，后续零上游请求）。 */
    private java.nio.file.Path coverCacheDir() {
        return java.nio.file.Path.of(props.processing().workRoot()).getParent()
                .resolve("covers");
    }

    public Cover fetchCover(long subjectId, String size) throws Exception {
        String sz = size == null || size.isBlank() ? "medium" : size;
        java.nio.file.Path cached = coverCacheDir().resolve(subjectId + "-" + sz + ".jpg");
        if (java.nio.file.Files.isRegularFile(cached)) {
            return new Cover(java.nio.file.Files.readAllBytes(cached), "image/jpeg");
        }
        Cover fetched = fetchCoverRemote(subjectId, sz);
        if (fetched != null) {
            try {
                java.nio.file.Files.createDirectories(cached.getParent());
                java.nio.file.Files.write(cached, fetched.bytes());
            } catch (Exception e) {
                log.warn("cover cache write failed for {}: {}", subjectId, e.getMessage());
            }
        }
        return fetched;
    }

    private Cover fetchCoverRemote(long subjectId, String size) throws Exception {
        String url = jdbc.queryForObject(
                "SELECT cover_url FROM bangumi_subjects WHERE subject_id = ? AND cover_url <> ''",
                String.class, subjectId);
        if (url == null) return null;
        if (url.startsWith("http://lain.bgm.tv")) {
            url = "https://" + url.substring("http://".length());
        }
        // 同图多尺寸：/pic/cover/{l|m|c|s}/… —— 网格用 common（300px 级）保证 retina 清晰度
        String seg = switch (size == null ? "medium" : size) {
            case "large" -> "l";
            case "common" -> "c";
            case "small" -> "s";
            default -> "m";
        };
        url = url.replaceFirst("/pic/cover/[lmcs]/", "/pic/cover/" + seg + "/");
        HttpRequest request = requestBuilder(url).GET().build();
        HttpResponse<byte[]> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) return null;
        String ct = resp.headers().firstValue("content-type").orElse("image/jpeg");
        return new Cover(resp.body(), ct);
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

    /** 本季新番（含资源覆盖标记），按星期分组供前端展示。 */
    public List<CalendarEntry> calendar() {
        return jdbc.query("""
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
            return out;
        });
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
        jdbc.update("""
                INSERT INTO bangumi_subjects
                    (subject_id, name, name_cn, cover_url, rating, air_date, air_weekday, summary,
                     in_calendar, tags)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (subject_id) DO UPDATE SET
                    name = EXCLUDED.name, name_cn = EXCLUDED.name_cn, cover_url = EXCLUDED.cover_url,
                    rating = EXCLUDED.rating, air_date = EXCLUDED.air_date,
                    air_weekday = EXCLUDED.air_weekday, summary = EXCLUDED.summary,
                    in_calendar = EXCLUDED.in_calendar, tags = EXCLUDED.tags, synced_at = now()
                """, s.subjectId(), s.name(), s.nameCn(), s.coverUrl(), s.rating(), s.airDate(),
                s.airWeekday(), s.summary(), inCalendar,
                s.tags().toArray(new String[0]));
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
