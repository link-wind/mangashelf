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

    public BangumiService(MangaShelfProperties props, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.props = props;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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
                          Double rating, LocalDate airDate, Integer airWeekday, String summary) {}

    /** 拉取本季日历并 upsert 缓存；返回条数。 */
    public int syncCalendar() throws Exception {
        JsonNode weeks = get("https://api.bgm.tv/calendar");
        int n = 0;
        for (JsonNode week : weeks) {
            int weekday = week.path("weekday").path("id").asInt();
            for (JsonNode it : week.path("items")) {
                upsertSubject(new Subject(
                        it.path("id").asLong(),
                        it.path("name").asText(""),
                        it.path("name_cn").asText(""),
                        it.path("images").path("common").asText(""),
                        it.path("rating").path("score").asDouble(0) == 0 ? null : it.path("rating").path("score").asDouble(),
                        parseDate(it.path("air_date").asText("")),
                        weekday,
                        it.path("summary").asText("")), true);
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
            out.add(new Subject(x.path("id").asLong(), x.path("name").asText(""),
                    x.path("name_cn").asText(""), x.path("images").path("common").asText(""),
                    null, parseDate(x.path("date").asText("")), null, ""));
        }
        return out;
    }

    /** 封面代理：按 subjectId 取图（不接受任意 URL，避免 SSRF；仅访问库内 cover_url）。 */
    public record Cover(byte[] bytes, String contentType) {}

    public Cover fetchCover(long subjectId) throws Exception {
        String url = jdbc.queryForObject(
                "SELECT cover_url FROM bangumi_subjects WHERE subject_id = ? AND cover_url <> ''",
                String.class, subjectId);
        if (url == null) return null;
        if (url.startsWith("http://lain.bgm.tv")) {
            url = "https://" + url.substring("http://".length());
        }
        HttpRequest request = requestBuilder(url).GET().build();
        HttpResponse<byte[]> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) return null;
        String ct = resp.headers().firstValue("content-type").orElse("image/jpeg");
        return new Cover(resp.body(), ct);
    }

    // ── 标题匹配（本地，Bangumi subject ↔ 资源目录条目）──

    /** 用缓存的本季 subject 名称/中文名归一化匹配目录条目；返回匹配数。 */
    public int matchCalendarToCatalog() {
        record S(long id, String name, String cn) {}
        List<S> subjects = new ArrayList<>();
        jdbc.query("SELECT subject_id, name, name_cn FROM bangumi_subjects WHERE in_calendar", rs -> {
            subjects.add(new S(rs.getLong(1), rs.getString(2), rs.getString(3)));
        });
        int matched = 0;
        for (S s : subjects) {
            for (String candidate : normalizeCandidates(s.name(), s.cn())) {
                int n = jdbc.update("""
                        UPDATE external_catalog_items SET bangumi_subject_id = ?
                        WHERE bangumi_subject_id IS NULL AND name = ?
                        """, s.id(), candidate);
                if (n == 0) {
                    n = jdbc.update("""
                            UPDATE external_catalog_items SET bangumi_subject_id = ?
                            WHERE bangumi_subject_id IS NULL AND name ILIKE ?
                            """, s.id(), candidate + "%");
                }
                matched += n;
                if (n > 0) break;
            }
        }
        return matched;
    }

    private static List<String> normalizeCandidates(String name, String cn) {
        List<String> out = new ArrayList<>();
        for (String raw : List.of(cn, name)) {
            if (raw == null || raw.isBlank()) continue;
            String v = raw.trim().replaceAll("\\s+", " ");
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
        return out;
    }

    public record CalendarEntry(long subjectId, String name, String nameCn, String coverUrl,
                                Double rating, Integer airWeekday, boolean covered,
                                String catalogItemId, String workId) {}

    /** 本季新番（含资源覆盖标记），按星期分组供前端展示。 */
    public List<CalendarEntry> calendar() {
        return jdbc.query("""
                SELECT DISTINCT ON (b.subject_id)
                       b.subject_id, b.name, b.name_cn, b.cover_url, b.rating, b.air_weekday,
                       (c.id IS NOT NULL) AS covered, c.id AS catalog_item_id, c.work_id
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
                        rs.getBoolean(7), rs.getString(8), rs.getString(9)));
            }
            out.sort(java.util.Comparator
                    .comparing((CalendarEntry e) -> e.airWeekday() == null ? 99 : e.airWeekday())
                    .thenComparing(e -> e.rating() == null ? -1 : e.rating(), java.util.Comparator.reverseOrder()));
            return out;
        });
    }

    // ── 内部 ──

    private void upsertSubject(Subject s, boolean inCalendar) {
        jdbc.update("""
                INSERT INTO bangumi_subjects
                    (subject_id, name, name_cn, cover_url, rating, air_date, air_weekday, summary, in_calendar)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (subject_id) DO UPDATE SET
                    name = EXCLUDED.name, name_cn = EXCLUDED.name_cn, cover_url = EXCLUDED.cover_url,
                    rating = EXCLUDED.rating, air_date = EXCLUDED.air_date,
                    air_weekday = EXCLUDED.air_weekday, summary = EXCLUDED.summary,
                    in_calendar = EXCLUDED.in_calendar, synced_at = now()
                """, s.subjectId(), s.name(), s.nameCn(), s.coverUrl(), s.rating(), s.airDate(),
                s.airWeekday(), s.summary(), inCalendar);
    }

    private HttpRequest.Builder requestBuilder(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("User-Agent", UA).header("Accept", "application/json");
    }

    private JsonNode get(String url) throws Exception {
        HttpResponse<String> resp = httpClient.send(requestBuilder(url).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Bangumi HTTP " + resp.statusCode());
        }
        return objectMapper.readTree(resp.body());
    }

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }
}
