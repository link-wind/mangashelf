package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 拷贝漫画：榜单、详情目录与公开章节图片走 HTTP API，搜索走浏览器；
 * 只有明确要求登录且已配置登录态时，章节图片才回退浏览器。
 */
@Component
public class CopyMangaBrowserSource implements MangaSource {

    private static final String WEB = "https://www.copy4000.com";
    private static final String API = "https://api.copy202602.com/api/v3";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36";
    private static final long VERSION_TTL_MS = 60 * 60 * 1000L;
    private static final long RANK_TTL_MS = 10 * 60 * 1000L;

    private final MangaBrowserResolveService browser;
    private final CopyMangaHttpDetailService httpDetail;
    private final CopyMangaHttpReaderService httpReader;
    private final ObjectMapper objectMapper;
    private final String cookie;
    private final String storageState;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12)).build();

    private volatile String version;
    private volatile long versionExpiresAt;
    private volatile List<RankItem> rankCache;
    private volatile long rankExpiresAt;

    public CopyMangaBrowserSource(MangaBrowserResolveService browser, CopyMangaHttpDetailService httpDetail,
                                  CopyMangaHttpReaderService httpReader, ObjectMapper objectMapper,
                                  @Value("${mangashelf.copymanga.cookie:}") String cookie,
                                  @Value("${mangashelf.copymanga.storage-state:}") String storageState) {
        this.browser = browser;
        this.httpDetail = httpDetail;
        this.httpReader = httpReader;
        this.objectMapper = objectMapper;
        this.cookie = cookie == null ? "" : cookie.trim();
        this.storageState = storageState == null ? "" : storageState.trim();
    }

    @Override
    public String id() {
        return "copymanga";
    }

    @Override
    public String name() {
        return "拷贝漫画";
    }

    @Override
    public String language() {
        return "zh";
    }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(Capability.RANK, Capability.SEARCH, Capability.DETAIL, Capability.READ);
    }

    @Override
    public String note() {
        return loginConfigured()
                ? "详情、目录与章节图片通过官方 API 获取；搜索使用浏览器解析，受限章节可回退登录态"
                : "详情、目录与章节图片通过官方 API 获取；搜索使用浏览器解析";
    }

    private boolean loginConfigured() {
        return !cookie.isBlank() || !storageState.isBlank();
    }

    // ── RANK（公开 API + 版本自举）──

    @Override
    public List<RankItem> rank(int limit) throws Exception {
        long now = System.currentTimeMillis();
        List<RankItem> cached = rankCache;
        if (cached != null && rankExpiresAt > now) {
            return cached.stream().limit(limit).toList();
        }
        String version = appVersion();
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/ranks"))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("region", "1")
                .header("webp", "1")
                .header("version", version)
                .header("appVersion", version)
                .GET().build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("拷贝漫画 HTTP " + resp.statusCode());
        }
        JsonNode root = objectMapper.readTree(resp.body());
        if (root.path("code").asInt(-1) != 200) {
            throw new IllegalStateException("拷贝漫画返回 code=" + root.path("code").asInt());
        }
        List<RankItem> out = new ArrayList<>();
        for (JsonNode row : root.path("results").path("list")) {
            JsonNode comic = row.path("comic");
            String pathWord = comic.path("path_word").asText("");
            String title = comic.path("name").asText("");
            if (pathWord.isBlank() || title.isBlank()) continue;
            List<String> authors = new ArrayList<>();
            for (JsonNode a : comic.path("author")) {
                String name = a.path("name").asText("");
                if (!name.isBlank()) authors.add(name);
            }
            out.add(new RankItem("copymanga", pathWord, title, authors,
                    hdCover(comic.path("cover").asText("")), false, "",
                    true, null, "copymanga", pathWord));
        }
        rankCache = out;
        rankExpiresAt = now + RANK_TTL_MS;
        return out.stream().limit(limit).toList();
    }

    /** 版本自举（拷贝风控要求请求头带最新版本号），缓存 1 小时。 */
    private String appVersion() throws Exception {
        long now = System.currentTimeMillis();
        String cached = version;
        if (cached != null && versionExpiresAt > now) {
            return cached;
        }
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(API + "/system/appVersion/last?platform=3"))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("region", "1")
                .header("webp", "1")
                .GET().build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("拷贝漫画版本接口 HTTP " + resp.statusCode());
        }
        JsonNode root = objectMapper.readTree(resp.body());
        String v = root.path("results").path("android").path("version").asText("");
        if (v.isBlank()) {
            throw new IllegalStateException("拷贝漫画版本自举失败");
        }
        version = v;
        versionExpiresAt = now + VERSION_TTL_MS;
        return v;
    }

    /** 封面缩略图后缀 .328x422.jpg → 原图（672px）。 */
    private static String hdCover(String url) {
        return url == null ? "" : url.replaceFirst("\\.\\d+x\\d+\\.jpg$", "");
    }

    // ── SEARCH（网页版搜索，Playwright）──

    @Override
    public List<SearchItem> search(String query, String lang, int limit) throws Exception {
        if (query == null || query.isBlank()) return List.of();
        String url = WEB + "/search?q=" + java.net.URLEncoder.encode(query,
                java.nio.charset.StandardCharsets.UTF_8);
        JsonNode result = browser.resolveSearch(url, cookie, storageState);
        List<SearchItem> out = new ArrayList<>();
        for (JsonNode item : result.path("results")) {
            if (out.size() >= limit) break;
            String id = item.path("id").asText("");
            String title = item.path("title").asText("");
            if (id.isBlank() || title.isBlank()) continue;
            String cover = item.path("cover").asText("");
            out.add(new SearchItem("copymanga", id, title, "",
                    cover.isBlank() ? null : hdCover(cover), null));
        }
        return out;
    }

    /** 题材清单与拷贝 API 拼音 slug 映射（/api/v3/comics?theme={slug}）。 */
    private static final java.util.Map<String, String> GENRE_SLUGS = java.util.Map.ofEntries(
            java.util.Map.entry("热血", "rexue"),
            java.util.Map.entry("恋爱", "aiqing"),
            java.util.Map.entry("搞笑", "huanlexiang"),
            java.util.Map.entry("冒险", "maoxian"),
            java.util.Map.entry("后宫", "hougong"),
            java.util.Map.entry("机甲", "jizhan"),
            java.util.Map.entry("科幻", "kehuan"),
            java.util.Map.entry("奇幻", "qihuan"),
            java.util.Map.entry("校园", "xiaoyuan"),
            java.util.Map.entry("悬疑", "xuanyi"),
            java.util.Map.entry("动作", "gedou"),
            java.util.Map.entry("治愈", "zhiyu"),
            java.util.Map.entry("日常", "shenghuo"),
            java.util.Map.entry("职场", "zhichang"),
            java.util.Map.entry("恐怖", "shengui"),
            java.util.Map.entry("百合", "baihe"),
            java.util.Map.entry("纯爱", "danmei"),
            java.util.Map.entry("历史", "lishi")
    );

    private static final List<String> GENRES = List.of(
            "恋爱", "搞笑", "冒险", "奇幻", "校园", "科幻", "动作", "悬疑",
            "治愈", "日常", "职场", "恐怖", "热血", "后宫", "机甲", "百合", "纯爱", "历史");

    @Override
    public List<String> genres() {
        return GENRES;
    }

    /** 按题材浏览：走 copymanga /api/v3/comics 筛选接口。 */
    @Override
    public List<SearchItem> searchByGenre(String genre, String lang, int limit) throws Exception {
        String slug = GENRE_SLUGS.getOrDefault(genre, genre);
        String url = "https://api.manga2026.xyz/api/v3/comics?theme="
                + java.net.URLEncoder.encode(slug, java.nio.charset.StandardCharsets.UTF_8)
                + "&offset=0&limit=" + Math.min(Math.max(limit, 1), 50)
                + "&ordering=-popular";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("User-Agent", "COPY/3.0.9")
                .header("Accept", "application/json")
                .header("version", "3.0.9")
                .header("versionCode", "83")
                .header("platform", "3")
                .header("region", "1")
                .header("webp", "1")
                .GET().build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new ApiException(502, "SOURCE_UNAVAILABLE", "拷贝漫画题材请求 HTTP " + resp.statusCode());
        }
        JsonNode list = objectMapper.readTree(resp.body()).path("results").path("list");
        List<SearchItem> out = new ArrayList<>();
        for (JsonNode item : list) {
            if (out.size() >= limit) break;
            String id = item.path("path_word").asText("");
            String title = item.path("name").asText("");
            if (id.isBlank() || title.isBlank()) continue;
            String cover = item.path("cover").asText("");
            out.add(new SearchItem("copymanga", id, title, "",
                    cover.isBlank() ? null : hdCover(cover), null));
        }
        return out;
    }

    @Override
    public Detail detail(String mangaId, String lang) throws Exception {
        return httpDetail.detail(mangaId);
    }

    @Override
    public Pages pages(String chapterId) throws Exception {
        try {
            return httpReader.pages(chapterId);
        } catch (ApiException error) {
            // 公开章节只走 HTTP；只有明确要求登录且已配置登录态时，才回退浏览器。
            if (error.getStatus() == 403 && "COPYMANGA_LOGIN_REQUIRED".equals(error.getCode())
                    && loginConfigured()) {
                return browserPages(chapterId);
            }
            throw error;
        }
    }

    private Pages browserPages(String chapterId) throws Exception {
        int sep = chapterId.indexOf('~');
        if (sep <= 0 || sep == chapterId.length() - 1) {
            throw new IllegalArgumentException("章节 ID 应为 {pathWord}~{uuid}：" + chapterId);
        }
        String pathWord = chapterId.substring(0, sep);
        String uuid = chapterId.substring(sep + 1);
        if (!pathWord.matches("[a-zA-Z0-9_-]+") || !uuid.matches("[0-9a-fA-F-]+")) {
            throw new IllegalArgumentException("章节 ID 格式不合法：" + chapterId);
        }
        JsonNode result = browser.resolveChapter(
                WEB + "/comic/" + pathWord + "/chapter/" + uuid, cookie, storageState);
        List<String> pages = new ArrayList<>();
        for (JsonNode p : result.path("pages")) {
            String url = p.asText("");
            if (!url.isBlank()) pages.add(url);
        }
        if (pages.isEmpty()) {
            throw new IllegalStateException("该章节未解析到页图");
        }
        return new Pages(pages, List.of(), false);
    }
}
