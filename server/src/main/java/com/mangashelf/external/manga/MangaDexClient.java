package com.mangashelf.external.manga;

import com.mangashelf.config.MangaShelfProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * MangaDex 客户端（M16）：官方开放 API，无需密钥。
 * 用途：搜索漫画、取章节列表、取章节页图地址（at-home CDN）。
 * 页图由浏览器直连 CDN；失败时前端回退到本服务的图片代理（允许域名白名单 + 磁盘缓存）。
 */
@Component
public class MangaDexClient {

    private static final Logger log = LoggerFactory.getLogger(MangaDexClient.class);
    private static final String API = "https://api.mangadex.org";
    private static final String UPLOADS = "https://uploads.mangadex.org";
    private static final String UA = "MangaShelf/0.1 (personal self-hosted)";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** 上游对 Java 客户端偶发握手阻断：TTL 缓存 + 失败时回退陈旧值，保证界面稳定。 */
    private record Cached(JsonNode value, long expiresAt) {}

    private final java.util.concurrent.ConcurrentHashMap<String, Cached> cache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public MangaDexClient(MangaShelfProperties props, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        var builder = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);
        String proxy = System.getenv("MANGASHELF_MANGADEX_PROXY");
        if (proxy != null && !proxy.isBlank()) {
            var uri = URI.create(proxy);
            builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(),
                    uri.getPort() == -1 ? 80 : uri.getPort())));
            log.info("MangaDex HTTP via proxy {}", uri);
        }
        this.httpClient = builder.build();
    }

    public record Manga(String id, String title, String altTitle, String description,
                        String coverUrl, String status, String year, List<String> tags,
                        List<String> langs) {}

    public record Chapter(String id, String chapter, String title, String lang,
                          int pages, String publishAt, boolean external) {}

    public record ChapterPages(String baseUrl, String hash, List<String> pages,
                               List<String> dataSaver) {}

    // ── 搜索 ──

    public List<Manga> search(String query, String lang, int limit) throws Exception {
        StringBuilder url = new StringBuilder(API + "/manga?limit=" + Math.min(limit, 30)
                + "&includes[]=cover_art&order[relevance]=desc&contentRating[]=safe"
                + "&contentRating[]=suggestive");
        if (query != null && !query.isBlank()) {
            url.append("&title=").append(java.net.URLEncoder.encode(query,
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        if (lang != null && !lang.isBlank()) {
            url.append("&availableTranslatedLanguage[]=").append(lang);
        }
        JsonNode root = getJson(url.toString());
        List<Manga> out = new ArrayList<>();
        for (JsonNode m : root.path("data")) {
            out.add(toManga(m));
        }
        return out;
    }

    /** 热门榜：按关注数降序。 */
    public List<Manga> popular(int limit) throws Exception {
        JsonNode root = getJson(API + "/manga?limit=" + Math.min(Math.max(limit, 1), 30)
                + "&includes[]=cover_art&order[followedCount]=desc"
                + "&contentRating[]=safe&contentRating[]=suggestive");
        List<Manga> out = new ArrayList<>();
        for (JsonNode m : root.path("data")) out.add(toManga(m));
        return out;
    }

    public Manga manga(String mangaId) throws Exception {
        JsonNode root = getJson(API + "/manga/" + mangaId + "?includes[]=cover_art");
        return toManga(root.path("data"));
    }

    // ── 章节 ──

    public List<Chapter> chapters(String mangaId, String lang, int limit) throws Exception {
        String url = API + "/manga/" + mangaId + "/feed?limit=" + Math.min(limit, 500)
                + "&order[chapter]=asc&includes[]=scanlation_group";
        if (lang != null && !lang.isBlank()) {
            url += "&translatedLanguage[]=" + lang;
        }
        JsonNode root = getJson(url);
        List<Chapter> out = new ArrayList<>();
        for (JsonNode c : root.path("data")) {
            JsonNode a = c.path("attributes");
            String externalUrl = a.path("externalUrl").asText("");
            out.add(new Chapter(c.path("id").asText(),
                    a.path("chapter").asText(""),
                    a.path("title").asText(""),
                    a.path("translatedLanguage").asText(""),
                    a.path("pages").asInt(0),
                    a.path("publishAt").asText(""),
                    !externalUrl.isBlank()));
        }
        return out;
    }

    public ChapterPages pages(String chapterId) throws Exception {
        JsonNode root = getJson(API + "/at-home/server/" + chapterId);
        List<String> data = new ArrayList<>();
        List<String> saver = new ArrayList<>();
        for (JsonNode p : root.path("chapter").path("data")) data.add(p.asText());
        for (JsonNode p : root.path("chapter").path("dataSaver")) saver.add(p.asText());
        return new ChapterPages(root.path("baseUrl").asText(),
                root.path("chapter").path("hash").asText(), data, saver);
    }

    // ── 图片代理（白名单）──

    /** 仅允许已知漫画源图片域名（MangaDex / 哔哩漫画 CDN），避免任意 URL 代理。 */
    public static boolean isAllowedImageHost(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            return host.equals("uploads.mangadex.org")
                    || host.endsWith(".mangadex.network")
                    || host.equals("mangadex.network")
                    || host.endsWith(".hdslb.com");
        } catch (Exception e) {
            return false;
        }
    }

    public record Image(byte[] bytes, String contentType) {}

    public Image fetchImage(String url) throws Exception {
        if (!isAllowedImageHost(url)) {
            throw new IllegalArgumentException("host not allowed");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30)).header("User-Agent", UA).GET().build();
        HttpResponse<byte[]> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() == 429) {
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }
        if (resp.statusCode() != 200) return null;
        String ct = resp.headers().firstValue("content-type").orElse("image/jpeg");
        return new Image(resp.body(), ct);
    }

    // ── 内部 ──

    private Manga toManga(JsonNode m) {
        JsonNode a = m.path("attributes");
        String title = a.path("title").path("en").asText("");
        if (title.isBlank()) {
            for (var it = a.path("title").properties().iterator(); it.hasNext(); ) {
                title = it.next().getValue().asText("");
                if (!title.isBlank()) break;
            }
        }
        String alt = a.path("altTitles").isArray()
                ? a.path("altTitles").path(0).path("zh").asText("") : "";
        if (alt.isBlank() && a.path("altTitles").isArray()) {
            alt = a.path("altTitles").path(0).path("zh-hk").asText("");
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode t : a.path("tags")) {
            tags.add(t.path("attributes").path("name").path("en").asText(""));
        }
        List<String> langs = new ArrayList<>();
        for (JsonNode l : a.path("availableTranslatedLanguages")) langs.add(l.asText());

        String coverUrl = null;
        for (JsonNode r : m.path("relationships")) {
            if ("cover_art".equals(r.path("type").asText())) {
                String file = r.path("attributes").path("fileName").asText("");
                if (!file.isBlank()) {
                    coverUrl = UPLOADS + "/covers/" + m.path("id").asText() + "/" + file + ".512.jpg";
                }
            }
        }
        String year = a.path("year").isNumber()
                ? String.valueOf(a.path("year").asInt()) : a.path("year").asText();
        String descZh = a.path("description").path("zh").asText();
        String descEn = a.path("description").path("en").asText();
        String desc = descZh.isBlank() ? descEn : descZh;
        return new Manga(m.path("id").asText(), title, alt, desc,
                coverUrl, a.path("status").asText(), year, tags, langs);
    }

    private JsonNode getJson(String url) throws Exception {
        return getJson(url, java.time.Duration.ofMinutes(10));
    }

    /** GET + 重试 + TTL 缓存；上游失败时若有陈旧缓存则回退（并记录）。 */
    private JsonNode getJson(String url, java.time.Duration ttl) throws Exception {
        Cached hit = cache.get(url);
        long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAt() > now) {
            return hit.value();
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT).header("User-Agent", UA).header("Accept", "application/json")
                .GET().build();
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new IllegalStateException("MangaDex HTTP " + resp.statusCode());
                }
                JsonNode value = objectMapper.readTree(resp.body());
                cache.put(url, new Cached(value, now + ttl.toMillis()));
                return value;
            } catch (java.io.IOException e) {
                last = e;
                log.warn("MangaDex attempt {} failed: {}", attempt, e.getMessage());
                Thread.sleep(600);
            }
        }
        if (hit != null) {
            log.warn("MangaDex upstream unavailable, serving stale cache for {}", url);
            return hit.value();
        }
        throw last != null ? last : new IllegalStateException("MangaDex request failed");
    }
}
