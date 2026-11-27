package com.mangashelf.external;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 搜索引擎收录与社交卡片基础设施：
 * 1) GET /robots.txt：动态输出当前域名的 Sitemap 地址与抓取边界；
 * 2) GET /sitemap.xml：输出核心入口页 + 热门番剧与去重漫画详情页（最多 ~1,000 条高价值 URL）；
 * 3) GET /api/v1/seo/render：仅在 Nginx 识别搜索引擎/社交预览爬虫时调用，输出带真实 TDK、Open Graph 与 JSON-LD 的轻量语义 HTML。
 */
@RestController
@Profile("api")
public class SeoController {

    private static final String SITE_NAME = "GemiNeko";
    private static final String SITE_TITLE = "GemiNeko · 杰米猫番漫空间";
    private static final String SITE_DESC = "GemiNeko（杰米猫）——无广告的番剧追更、每周新番时刻表、日漫国漫韩漫高分索引与跨源漫画阅读空间，支持 Bangumi / AniList 口碑评分与进度同步。";
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
    private static final Pattern SAFE_HOST = Pattern.compile("^[A-Za-z0-9.\\-:]{1,253}$");
    private static final Pattern CATALOG_PATH = Pattern.compile("^/catalog/([0-9a-fA-F\\-]{36})/?$");
    private static final Pattern MANGA_EXT_PATH = Pattern.compile("^/manga-ext/([^/]+)/?$");

    private final JdbcTemplate jdbc;

    public SeoController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping(value = "/robots.txt", produces = "text/plain; charset=utf-8")
    public ResponseEntity<String> robots(HttpServletRequest req) {
        String base = resolveBaseUrl(req);
        String body = """
                User-agent: *
                Allow: /
                Disallow: /api/
                Disallow: /media/
                Disallow: /login
                Disallow: /me
                Disallow: /settings
                Disallow: /search
                Disallow: /uploads
                Disallow: /read/
                Disallow: /watch/
                Disallow: /browse/
                Disallow: /manga-ext/read/

                Sitemap: %s/sitemap.xml
                """.formatted(base);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .body(body);
    }

    @GetMapping(value = "/sitemap.xml", produces = "application/xml; charset=utf-8")
    public ResponseEntity<String> sitemap(HttpServletRequest req) {
        String base = resolveBaseUrl(req);
        String today = ISO_DATE.format(Instant.now());
        StringBuilder xml = new StringBuilder(32_768);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        appendUrl(xml, base + "/", today, "daily", "1.0");
        appendUrl(xml, base + "/schedule", today, "daily", "0.9");
        appendUrl(xml, base + "/index", today, "daily", "0.9");
        appendUrl(xml, base + "/manga", today, "daily", "0.9");
        appendUrl(xml, base + "/index?kind=manga", today, "daily", "0.8");

        List<SitemapRow> animeRows = jdbc.query("""
                SELECT id::text AS loc_id,
                       COALESCE(resource_updated_at, synced_at) AS updated_at
                  FROM external_catalog_items
                 WHERE category_id IN (29, 30, 31, 32, 33)
                 ORDER BY region_rank ASC, recommend_score DESC, id DESC
                 LIMIT 500
                """, (rs, i) -> new SitemapRow(
                "/catalog/" + rs.getString("loc_id"),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant()));
        for (SitemapRow row : animeRows) {
            appendUrl(xml, base + row.path(), row.updatedAt() == null ? today : ISO_DATE.format(row.updatedAt()),
                    "weekly", "0.7");
        }

        List<SitemapRow> mangaRows = jdbc.query("""
                WITH dedup AS (
                    SELECT DISTINCT ON (COALESCE(NULLIF(title_key, ''), source || ':' || source_item_id))
                           source, source_item_id, synced_at, region_rank, recommend_score
                      FROM manga_catalog_items
                     ORDER BY COALESCE(NULLIF(title_key, ''), source || ':' || source_item_id),
                              CASE source WHEN 'copymanga' THEN 0 WHEN 'tencent' THEN 1 WHEN 'bilibili' THEN 2 ELSE 3 END,
                              synced_at DESC
                )
                SELECT source, source_item_id, synced_at
                  FROM dedup
                 ORDER BY region_rank ASC, recommend_score DESC, synced_at DESC
                 LIMIT 500
                """, (rs, i) -> new SitemapRow(
                "/manga-ext/" + encodePathSegment(rs.getString("source_item_id"))
                        + "?source=" + encodeQueryValue(rs.getString("source")),
                rs.getTimestamp("synced_at") == null ? null : rs.getTimestamp("synced_at").toInstant()));
        for (SitemapRow row : mangaRows) {
            appendUrl(xml, base + row.path(), row.updatedAt() == null ? today : ISO_DATE.format(row.updatedAt()),
                    "weekly", "0.7");
        }

        xml.append("</urlset>\n");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(15, TimeUnit.MINUTES).cachePublic())
                .body(xml.toString());
    }

    /**
     * 爬虫轻量预渲染入口：返回带完整语义标题、描述、Open Graph、JSON-LD 与站内可抓取链接的 HTML。
     * 带 Vary: User-Agent 与 no-cache，避免 CDN 误把爬虫 HTML 缓存给普通浏览器用户。
     */
    @GetMapping(value = "/api/v1/seo/render", produces = "text/html; charset=utf-8")
    public ResponseEntity<String> renderForCrawler(
            HttpServletRequest req,
            @RequestParam(defaultValue = "/") String path,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String source) {
        String base = resolveBaseUrl(req);
        String cleanPath = normalizePath(path);

        if (isNoIndexPath(cleanPath)) {
            return htmlResponse(buildPage(
                    base, cleanPath, SITE_TITLE, SITE_DESC,
                    base + "/brand-icon.png", "website", true,
                    buildWebSiteJsonLd(base),
                    "<p>" + escapeHtml(SITE_DESC) + "</p>"));
        }

        Matcher catalogMatcher = CATALOG_PATH.matcher(cleanPath);
        if (catalogMatcher.matches()) {
            return htmlResponse(renderCatalogDetail(base, UUID.fromString(catalogMatcher.group(1))));
        }

        Matcher mangaMatcher = MANGA_EXT_PATH.matcher(cleanPath);
        if (mangaMatcher.matches()) {
            return htmlResponse(renderMangaDetail(base, mangaMatcher.group(1), source));
        }

        if ("/schedule".equals(cleanPath)) {
            return htmlResponse(renderSchedule(base));
        }
        if ("/manga".equals(cleanPath) || ("/index".equals(cleanPath) && "manga".equalsIgnoreCase(kind))) {
            return htmlResponse(renderMangaHub(base, cleanPath));
        }
        if ("/index".equals(cleanPath) || "/anime".equals(cleanPath)) {
            return htmlResponse(renderAnimeHub(base));
        }

        return htmlResponse(renderHome(base));
    }

    private String renderHome(String base) {
        List<LinkItem> anime = queryTopAnimeLinks(24);
        List<LinkItem> manga = queryTopMangaLinks(24);
        StringBuilder body = new StringBuilder();
        body.append("<p>").append(escapeHtml(SITE_DESC)).append("</p>");
        appendSectionLinks(body, "精选推荐番剧", anime);
        appendSectionLinks(body, "热门高分漫画", manga);
        return buildPage(base, "/", SITE_TITLE, SITE_DESC, base + "/brand-icon.png",
                "website", false, buildWebSiteJsonLd(base), body.toString());
    }

    private String renderSchedule(String base) {
        record CalRow(String name, Double rating, Integer weekday, String catalogId) {}
        List<CalRow> rows = jdbc.query("""
                SELECT COALESCE(NULLIF(b.name_cn, ''), b.name) AS title,
                       b.rating,
                       b.air_weekday,
                       (SELECT e.id::text FROM external_catalog_items e
                         WHERE e.bangumi_subject_id = b.subject_id
                         ORDER BY e.episode_count DESC LIMIT 1) AS catalog_id
                  FROM bangumi_subjects b
                 WHERE b.in_calendar = true
                 ORDER BY b.air_weekday ASC NULLS LAST, b.rating DESC NULLS LAST
                 LIMIT 60
                """, (rs, i) -> new CalRow(
                rs.getString("title"),
                rs.getObject("rating") == null ? null : rs.getDouble("rating"),
                rs.getObject("air_weekday") == null ? null : rs.getInt("air_weekday"),
                rs.getString("catalog_id")));

        String desc = "GemiNeko 每周新番放送时刻表：按星期查看本季在播番剧、Bangumi 评分，支持一键导出 .ics 日历订阅追番提醒。";
        StringBuilder body = new StringBuilder();
        body.append("<p>").append(escapeHtml(desc)).append("</p><section><h2>本季新番放送表</h2><ul>");
        for (CalRow r : rows) {
            String day = weekdayLabel(r.weekday());
            String score = r.rating() != null && r.rating() > 0 ? String.format(" ★%.1f", r.rating()) : "";
            body.append("<li>");
            if (r.catalogId() != null && !r.catalogId().isBlank()) {
                body.append("<a href=\"/catalog/").append(escapeHtml(r.catalogId())).append("\">")
                        .append(escapeHtml(r.name())).append("</a>");
            } else {
                body.append(escapeHtml(r.name()));
            }
            body.append(" <span>").append(escapeHtml(day + score)).append("</span></li>");
        }
        body.append("</ul></section>");
        return buildPage(base, "/schedule", "新番时刻表 · " + SITE_NAME, desc,
                base + "/brand-icon.png", "website", false, buildWebSiteJsonLd(base), body.toString());
    }

    private String renderAnimeHub(String base) {
        String desc = "GemiNeko 番剧与动画索引：按日漫、国漫、韩漫、欧美地区与 Bangumi/AniList 口碑评分、题材、年代快速筛选高分动画。";
        StringBuilder body = new StringBuilder("<p>").append(escapeHtml(desc)).append("</p>");
        appendSectionLinks(body, "推荐番剧索引", queryTopAnimeLinks(40));
        return buildPage(base, "/index", "番剧索引 · " + SITE_NAME, desc,
                base + "/brand-icon.png", "website", false, buildWebSiteJsonLd(base), body.toString());
    }

    private String renderMangaHub(String base, String path) {
        String desc = "GemiNeko 漫画发现与跨源索引：汇聚日漫、国漫、韩漫口碑高分精选与多源聚合目录，支持进度同步与无广告沉浸阅读。";
        StringBuilder body = new StringBuilder("<p>").append(escapeHtml(desc)).append("</p>");
        appendSectionLinks(body, "精选漫画目录", queryTopMangaLinks(40));
        return buildPage(base, path, "漫画 · " + SITE_NAME, desc,
                base + "/brand-icon.png", "website", false, buildWebSiteJsonLd(base), body.toString());
    }

    private String renderCatalogDetail(String base, UUID id) {
        record AnimeDetail(String title, String origName, String remarks, String region,
                           int episodes, Long subjectId, Double rating, int ratingCount,
                           String summary, List<String> tags) {}
        List<AnimeDetail> list = jdbc.query("""
                SELECT COALESCE(NULLIF(b.name_cn, ''), e.name) AS title,
                       COALESCE(b.name, '') AS orig_name,
                       e.remarks,
                       e.region,
                       e.episode_count,
                       e.bangumi_subject_id,
                       COALESCE(e.external_rating, b.rating) AS score,
                       GREATEST(e.external_rating_count, COALESCE(b.rating_count, 0)) AS votes,
                       COALESCE(b.summary, '') AS summary,
                       e.tags
                  FROM external_catalog_items e
                  LEFT JOIN bangumi_subjects b ON b.subject_id = e.bangumi_subject_id
                 WHERE e.id = ?
                 LIMIT 1
                """, (rs, i) -> new AnimeDetail(
                rs.getString("title"),
                rs.getString("orig_name"),
                rs.getString("remarks"),
                rs.getString("region"),
                rs.getInt("episode_count"),
                rs.getObject("bangumi_subject_id") == null ? null : rs.getLong("bangumi_subject_id"),
                rs.getObject("score") == null ? null : rs.getDouble("score"),
                rs.getInt("votes"),
                rs.getString("summary"),
                readStringArray(rs.getArray("tags"))), id);

        if (list.isEmpty()) {
            return renderHome(base);
        }
        AnimeDetail d = list.getFirst();
        String title = d.title() + " · " + SITE_NAME;
        String desc = buildAnimeDescription(d.title(), d.region(), d.remarks(), d.episodes(), d.rating(), d.summary());
        String image = d.subjectId() != null
                ? base + "/api/v1/bangumi-covers/" + d.subjectId() + "?size=large"
                : base + "/api/v1/catalog-covers/" + id;
        String canonicalPath = "/catalog/" + id;

        StringBuilder jsonLd = new StringBuilder();
        jsonLd.append("{\"@context\":\"https://schema.org\",\"@type\":\"TVSeries\"")
                .append(",\"name\":\"").append(escapeJson(d.title())).append("\"")
                .append(",\"url\":\"").append(escapeJson(base + canonicalPath)).append("\"")
                .append(",\"image\":\"").append(escapeJson(image)).append("\"")
                .append(",\"description\":\"").append(escapeJson(desc)).append("\"");
        if (d.episodes() > 0) {
            jsonLd.append(",\"numberOfEpisodes\":").append(d.episodes());
        }
        if (d.rating() != null && d.rating() > 0) {
            jsonLd.append(",\"aggregateRating\":{\"@type\":\"AggregateRating\",\"ratingValue\":")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", d.rating()))
                    .append(",\"bestRating\":10,\"worstRating\":1,\"ratingCount\":")
                    .append(Math.max(1, d.ratingCount())).append("}");
        }
        jsonLd.append("}");

        StringBuilder body = new StringBuilder();
        if (!d.origName().isBlank() && !d.origName().equals(d.title())) {
            body.append("<p>原名：").append(escapeHtml(d.origName())).append("</p>");
        }
        body.append("<p>").append(escapeHtml(desc)).append("</p>");
        if (!d.tags().isEmpty()) {
            body.append("<p>题材标签：").append(escapeHtml(String.join(" / ", d.tags()))).append("</p>");
        }
        return buildPage(base, canonicalPath, title, desc, image, "video.tv_show", false, jsonLd.toString(), body.toString());
    }

    private String renderMangaDetail(String base, String rawMangaId, String source) {
        String mangaId = java.net.URLDecoder.decode(rawMangaId, StandardCharsets.UTF_8);
        record MangaDetail(String source, String sourceItemId, String title, String author,
                           String remarks, String region, Double rating, int ratingCount,
                           List<String> tags) {}
        String sql = source != null && !source.isBlank()
                ? """
                  SELECT source, source_item_id, name, alt_title, remarks, region, rating, rating_count, tags
                    FROM manga_catalog_items
                   WHERE source = ? AND source_item_id = ?
                   LIMIT 1
                  """
                : """
                  SELECT source, source_item_id, name, alt_title, remarks, region, rating, rating_count, tags
                    FROM manga_catalog_items
                   WHERE source_item_id = ?
                   ORDER BY CASE source WHEN 'copymanga' THEN 0 WHEN 'tencent' THEN 1 WHEN 'bilibili' THEN 2 ELSE 3 END,
                            synced_at DESC
                   LIMIT 1
                  """;
        List<MangaDetail> list = source != null && !source.isBlank()
                ? jdbc.query(sql, (rs, i) -> new MangaDetail(
                        rs.getString("source"), rs.getString("source_item_id"), rs.getString("name"),
                        rs.getString("alt_title"), rs.getString("remarks"), rs.getString("region"),
                        rs.getObject("rating") == null ? null : rs.getDouble("rating"),
                        rs.getInt("rating_count"), readStringArray(rs.getArray("tags"))), source, mangaId)
                : jdbc.query(sql, (rs, i) -> new MangaDetail(
                        rs.getString("source"), rs.getString("source_item_id"), rs.getString("name"),
                        rs.getString("alt_title"), rs.getString("remarks"), rs.getString("region"),
                        rs.getObject("rating") == null ? null : rs.getDouble("rating"),
                        rs.getInt("rating_count"), readStringArray(rs.getArray("tags"))), mangaId);

        if (list.isEmpty()) {
            return renderMangaHub(base, "/manga");
        }
        MangaDetail d = list.getFirst();
        String title = d.title() + " · " + SITE_NAME;
        String desc = buildMangaDescription(d.title(), d.author(), d.region(), d.remarks(), d.rating(), d.tags());
        String canonicalPath = "/manga-ext/" + encodePathSegment(d.sourceItemId())
                + "?source=" + encodeQueryValue(d.source());
        String image = base + "/brand-icon.png";

        StringBuilder jsonLd = new StringBuilder();
        jsonLd.append("{\"@context\":\"https://schema.org\",\"@type\":\"Book\"")
                .append(",\"name\":\"").append(escapeJson(d.title())).append("\"")
                .append(",\"url\":\"").append(escapeJson(base + canonicalPath)).append("\"")
                .append(",\"image\":\"").append(escapeJson(image)).append("\"")
                .append(",\"description\":\"").append(escapeJson(desc)).append("\"");
        if (d.author() != null && !d.author().isBlank()) {
            jsonLd.append(",\"author\":{\"@type\":\"Person\",\"name\":\"").append(escapeJson(d.author())).append("\"}");
        }
        if (d.rating() != null && d.rating() > 0) {
            jsonLd.append(",\"aggregateRating\":{\"@type\":\"AggregateRating\",\"ratingValue\":")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", d.rating()))
                    .append(",\"bestRating\":10,\"worstRating\":1,\"ratingCount\":")
                    .append(Math.max(1, d.ratingCount())).append("}");
        }
        jsonLd.append("}");

        StringBuilder body = new StringBuilder();
        if (d.author() != null && !d.author().isBlank()) {
            body.append("<p>作者：").append(escapeHtml(d.author())).append("</p>");
        }
        body.append("<p>").append(escapeHtml(desc)).append("</p>");
        if (!d.tags().isEmpty()) {
            body.append("<p>题材：").append(escapeHtml(String.join(" / ", d.tags()))).append("</p>");
        }
        return buildPage(base, canonicalPath, title, desc, image, "book", false, jsonLd.toString(), body.toString());
    }

    private List<LinkItem> queryTopAnimeLinks(int limit) {
        return jdbc.query("""
                SELECT e.id::text AS id,
                       COALESCE(NULLIF(b.name_cn, ''), e.name) AS title,
                       COALESCE(e.external_rating, b.rating) AS score
                  FROM external_catalog_items e
                  LEFT JOIN bangumi_subjects b ON b.subject_id = e.bangumi_subject_id
                 WHERE e.category_id IN (29, 30, 31, 32, 33)
                 ORDER BY e.region_rank ASC, e.recommend_score DESC, e.id DESC
                 LIMIT ?
                """, (rs, i) -> {
            Double s = rs.getObject("score") == null ? null : rs.getDouble("score");
            String meta = s != null && s > 0 ? String.format("★%.1f", s) : "";
            return new LinkItem("/catalog/" + rs.getString("id"), rs.getString("title"), meta);
        }, limit);
    }

    private List<LinkItem> queryTopMangaLinks(int limit) {
        return jdbc.query("""
                WITH dedup AS (
                    SELECT DISTINCT ON (COALESCE(NULLIF(title_key, ''), source || ':' || source_item_id))
                           source, source_item_id, name, rating, region_rank, recommend_score, synced_at
                      FROM manga_catalog_items
                     ORDER BY COALESCE(NULLIF(title_key, ''), source || ':' || source_item_id),
                              CASE source WHEN 'copymanga' THEN 0 WHEN 'tencent' THEN 1 WHEN 'bilibili' THEN 2 ELSE 3 END,
                              synced_at DESC
                )
                SELECT source, source_item_id, name, rating
                  FROM dedup
                 ORDER BY region_rank ASC, recommend_score DESC, synced_at DESC
                 LIMIT ?
                """, (rs, i) -> {
            Double s = rs.getObject("rating") == null ? null : rs.getDouble("rating");
            String meta = s != null && s > 0 ? String.format("★%.1f", s) : "";
            String href = "/manga-ext/" + encodePathSegment(rs.getString("source_item_id"))
                    + "?source=" + encodeQueryValue(rs.getString("source"));
            return new LinkItem(href, rs.getString("name"), meta);
        }, limit);
    }

    private static void appendSectionLinks(StringBuilder body, String heading, List<LinkItem> items) {
        if (items.isEmpty()) return;
        body.append("<section><h2>").append(escapeHtml(heading)).append("</h2><ul>");
        for (LinkItem it : items) {
            body.append("<li><a href=\"").append(escapeHtml(it.href())).append("\">")
                    .append(escapeHtml(it.title())).append("</a>");
            if (!it.meta().isBlank()) {
                body.append(" <span>").append(escapeHtml(it.meta())).append("</span>");
            }
            body.append("</li>");
        }
        body.append("</ul></section>");
    }

    private static ResponseEntity<String> htmlResponse(String html) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .header("Vary", "User-Agent")
                .header("Cache-Control", "no-cache")
                .body(html);
    }

    private static String buildPage(String base, String canonicalPath, String title, String description,
                                    String image, String ogType, boolean noIndex,
                                    String jsonLd, String bodyHtml) {
        String canonical = base + canonicalPath;
        String robots = noIndex ? "noindex, nofollow" : "index, follow";
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <meta name="referrer" content="no-referrer" />
                  <title>%s</title>
                  <meta name="description" content="%s" />
                  <meta name="robots" content="%s" />
                  <link rel="canonical" href="%s" />
                  <link rel="icon" type="image/png" href="%s/favicon.png" />
                  <meta property="og:type" content="%s" />
                  <meta property="og:site_name" content="%s" />
                  <meta property="og:locale" content="zh_CN" />
                  <meta property="og:title" content="%s" />
                  <meta property="og:description" content="%s" />
                  <meta property="og:url" content="%s" />
                  <meta property="og:image" content="%s" />
                  <meta name="twitter:card" content="summary_large_image" />
                  <meta name="twitter:title" content="%s" />
                  <meta name="twitter:description" content="%s" />
                  <meta name="twitter:image" content="%s" />
                  <script type="application/ld+json">%s</script>
                </head>
                <body>
                  <header>
                    <nav>
                      <a href="/">GemiNeko 首页</a> ·
                      <a href="/schedule">新番时刻表</a> ·
                      <a href="/index">番剧索引</a> ·
                      <a href="/manga">漫画空间</a>
                    </nav>
                  </header>
                  <main>
                    <h1>%s</h1>
                    %s
                  </main>
                </body>
                </html>
                """.formatted(
                escapeHtml(title), escapeHtml(description), robots, escapeHtml(canonical),
                escapeHtml(base), escapeHtml(ogType), SITE_NAME,
                escapeHtml(title), escapeHtml(description), escapeHtml(canonical), escapeHtml(image),
                escapeHtml(title), escapeHtml(description), escapeHtml(image),
                jsonLd,
                escapeHtml(title), bodyHtml);
    }

    private static String buildWebSiteJsonLd(String base) {
        return "{\"@context\":\"https://schema.org\",\"@type\":\"WebSite\","
                + "\"name\":\"GemiNeko\","
                + "\"alternateName\":\"杰米猫\","
                + "\"url\":\"" + escapeJson(base + "/") + "\","
                + "\"description\":\"" + escapeJson(SITE_DESC) + "\"}";
    }

    static String buildAnimeDescription(String title, String region, String remarks, int episodes,
                                        Double rating, String summary) {
        if (summary != null && !summary.isBlank()) {
            String compact = summary.replaceAll("\\s+", " ").trim();
            if (compact.length() > 155) {
                compact = compact.substring(0, 152) + "…";
            }
            return compact;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("在 GemiNeko（杰米猫）查看《").append(title).append("》");
        String reg = regionLabel(region);
        if (!reg.isBlank()) sb.append("（").append(reg).append("）");
        if (rating != null && rating > 0) {
            sb.append("，口碑评分 ").append(String.format(java.util.Locale.ROOT, "%.1f", rating));
        }
        if (remarks != null && !remarks.isBlank()) {
            sb.append("，").append(remarks);
        } else if (episodes > 0) {
            sb.append("，共 ").append(episodes).append(" 集");
        }
        sb.append("。无广告追番与进度同步。");
        return sb.toString();
    }

    static String buildMangaDescription(String title, String author, String region, String remarks,
                                        Double rating, List<String> tags) {
        StringBuilder sb = new StringBuilder();
        sb.append("在 GemiNeko（杰米猫）浏览漫画《").append(title).append("》");
        if (author != null && !author.isBlank()) sb.append("（作者：").append(author).append("）");
        String reg = regionLabel(region);
        if (!reg.isBlank()) sb.append("，").append(reg);
        if (rating != null && rating > 0) {
            sb.append("，评分 ").append(String.format(java.util.Locale.ROOT, "%.1f", rating));
        }
        if (remarks != null && !remarks.isBlank()) sb.append("，").append(remarks);
        if (tags != null && !tags.isEmpty()) {
            sb.append("，题材：").append(String.join("、", tags.subList(0, Math.min(tags.size(), 5))));
        }
        sb.append("。");
        return sb.toString();
    }

    static String resolveBaseUrl(HttpServletRequest req) {
        String proto = firstHeaderValue(req.getHeader("X-Forwarded-Proto"));
        if (!"http".equalsIgnoreCase(proto) && !"https".equalsIgnoreCase(proto)) {
            proto = req.getScheme();
        }
        if (!"http".equalsIgnoreCase(proto) && !"https".equalsIgnoreCase(proto)) {
            proto = "https";
        }
        proto = proto.toLowerCase(java.util.Locale.ROOT);

        String host = firstHeaderValue(req.getHeader("X-Forwarded-Host"));
        if (host.isBlank()) host = firstHeaderValue(req.getHeader("Host"));
        if (host.isBlank() || !SAFE_HOST.matcher(host).matches()) {
            String serverName = req.getServerName() == null ? "localhost" : req.getServerName();
            int port = req.getServerPort();
            boolean defaultPort = ("http".equals(proto) && port == 80) || ("https".equals(proto) && port == 443) || port <= 0;
            host = defaultPort ? serverName : serverName + ":" + port;
        }
        return proto + "://" + host;
    }

    private static String firstHeaderValue(String raw) {
        if (raw == null || raw.isBlank()) return "";
        int comma = raw.indexOf(',');
        return (comma >= 0 ? raw.substring(0, comma) : raw).trim();
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        int q = path.indexOf('?');
        String clean = q >= 0 ? path.substring(0, q) : path;
        if (!clean.startsWith("/")) clean = "/" + clean;
        return clean;
    }

    private static boolean isNoIndexPath(String path) {
        return path.equals("/login") || path.equals("/me") || path.equals("/settings")
                || path.equals("/search") || path.equals("/uploads")
                || path.startsWith("/browse/") || path.startsWith("/watch/")
                || path.startsWith("/read/") || path.startsWith("/manga-ext/read/");
    }

    private static void appendUrl(StringBuilder xml, String loc, String lastmod, String changefreq, String priority) {
        xml.append("  <url><loc>").append(escapeXml(loc))
                .append("</loc><lastmod>").append(escapeXml(lastmod))
                .append("</lastmod><changefreq>").append(changefreq)
                .append("</changefreq><priority>").append(priority)
                .append("</priority></url>\n");
    }

    private static String regionLabel(String region) {
        if (region == null) return "";
        return switch (region) {
            case "JP" -> "日漫";
            case "CN" -> "国漫";
            case "KR" -> "韩漫";
            case "OVERSEAS" -> "欧美";
            default -> "";
        };
    }

    private static String weekdayLabel(Integer weekday) {
        if (weekday == null) return "";
        return switch (weekday) {
            case 1 -> "周一";
            case 2 -> "周二";
            case 3 -> "周三";
            case 4 -> "周四";
            case 5 -> "周五";
            case 6 -> "周六";
            case 7 -> "周日";
            default -> "";
        };
    }

    private static List<String> readStringArray(Array sqlArray) {
        if (sqlArray == null) return List.of();
        try {
            Object raw = sqlArray.getArray();
            if (raw instanceof String[] arr) {
                List<String> out = new ArrayList<>(arr.length);
                for (String s : arr) {
                    if (s != null && !s.isBlank()) out.add(s);
                }
                return out;
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    private static String encodePathSegment(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQueryValue(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private record SitemapRow(String path, Instant updatedAt) {}
    private record LinkItem(String href, String title, String meta) {}
}
