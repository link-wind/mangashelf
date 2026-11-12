package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CopyMangaHttpDetailService {

    private static final String API = "https://api.manga2026.xyz/api/v3";
    private static final String USER_AGENT = "COPY/3.0.9";
    private static final String VERSION = "3.0.9";
    private static final String VERSION_CODE = "83";
    private static final Pattern MANGA_ID = Pattern.compile("[a-zA-Z0-9_-]{1,128}");
    private static final Pattern CHAPTER_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int PAGE_SIZE = 100;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CHAPTERS = 10000;
    private static final int MAX_CACHE_ENTRIES = 128;
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private record Cached(MangaSource.Detail detail, long expiresAt) {}

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI api;
    private final Clock clock;
    private final Map<String, Cached> cache = new LinkedHashMap<>();
    private final Object[] locks = new Object[32];

    @Autowired
    public CopyMangaHttpDetailService(ObjectMapper mapper,
                                     @Value("${mangashelf.copymanga.proxy:}") String proxy) {
        this(mapper, createClient(proxy), URI.create(API), Clock.systemUTC());
    }

    private static HttpClient createClient(String proxy) {
        ProxySelector selector = HttpClient.Builder.NO_PROXY;
        if (proxy != null && !proxy.isBlank()) {
            URI target = URI.create(proxy);
            if (!"http".equals(target.getScheme()) || target.getHost() == null
                    || target.getUserInfo() != null || target.getPort() < 1 || target.getPort() > 65535
                    || target.getQuery() != null || target.getFragment() != null
                    || !(target.getPath().isEmpty() || target.getPath().equals("/"))) {
                throw new IllegalArgumentException("拷贝 HTTP 代理必须是带端口且不含凭据的 http 地址");
            }
            selector = ProxySelector.of(new InetSocketAddress(target.getHost(), target.getPort()));
        }
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).proxy(selector).build();
    }

    CopyMangaHttpDetailService(ObjectMapper mapper, HttpClient client, URI api, Clock clock) {
        this.mapper = mapper;
        this.client = client;
        this.api = api;
        this.clock = clock;
        for (int index = 0; index < locks.length; index++) locks[index] = new Object();
    }

    public MangaSource.Detail detail(String mangaId) {
        if (mangaId == null || !MANGA_ID.matcher(mangaId).matches()) {
            throw new ApiException(400, "COPYMANGA_INVALID_ID", "拷贝漫画作品标识不合法");
        }
        synchronized (locks[Math.floorMod(mangaId.hashCode(), locks.length)]) {
            synchronized (cache) {
                Cached hit = cache.get(mangaId);
                if (hit != null && hit.expiresAt() > clock.millis()) return hit.detail();
                cache.remove(mangaId);
            }
            MangaSource.Detail result = load(mangaId);
            synchronized (cache) {
                cache.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= clock.millis());
                if (cache.size() >= MAX_CACHE_ENTRIES) {
                    String oldest = cache.entrySet().stream()
                            .min(Comparator.comparingLong(entry -> entry.getValue().expiresAt()))
                            .orElseThrow().getKey();
                    cache.remove(oldest);
                }
                cache.put(mangaId, new Cached(result, clock.millis() + CACHE_TTL.toMillis()));
            }
            return result;
        }
    }

    private MangaSource.Detail load(String mangaId) {
        try {
            return loadOnce(mangaId);
        } catch (ApiException error) {
            if (!"COPYMANGA_DIRECTORY_EMPTY".equals(error.getCode())) throw error;
        }
        try {
            return loadOnce(mangaId);
        } catch (ApiException error) {
            if ("COPYMANGA_DIRECTORY_EMPTY".equals(error.getCode())) {
                throw new ApiException(502, "COPYMANGA_DIRECTORY_UNAVAILABLE",
                        "拷贝漫画暂未返回公开目录，请稍后重试");
            }
            throw error;
        }
    }

    private MangaSource.Detail loadOnce(String mangaId) {
        JsonNode detail = get("/comic2/" + mangaId + "?platform=3&request_id=" + UUID.randomUUID());
        int code = detail.path("code").asInt(-1);
        if (code == 210) {
            throw new ApiException(404, "COPYMANGA_NOT_FOUND", "拷贝漫画作品不存在或已下架");
        }
        if (code != 200) {
            throw failure("COPYMANGA_UPSTREAM_REJECTED", "拷贝漫画目录接口拒绝请求，请稍后重试");
        }
        JsonNode results = detail.path("results");
        JsonNode comic = results.path("comic");
        JsonNode groups = results.path("groups");
        if (!results.isObject() || !comic.isObject() || !groups.isObject()) {
            throw invalidDirectory();
        }
        String pathWord = comic.path("path_word").asText("");
        String title = comic.path("name").asText("").trim();
        if (!mangaId.equals(pathWord) || title.isBlank() || comic.path("uuid").asText("").isBlank()) {
            throw invalidDirectory();
        }
        if (groups.isEmpty()) {
            throw new ApiException(502, "COPYMANGA_DIRECTORY_EMPTY", "拷贝漫画公开目录为空");
        }
        List<MangaSource.ChapterItem> chapters = chapters(mangaId, groups);
        if (chapters.isEmpty()) {
            throw new ApiException(502, "COPYMANGA_DIRECTORY_EMPTY", "拷贝漫画公开目录为空");
        }

        String cover = comic.path("cover").asText("").trim();
        cover = cover.replaceFirst("\\.\\d+x\\d+\\.jpg$", "");
        if (!cover.isBlank() && !safeCover(cover)) {
            throw failure("COPYMANGA_DETAIL_INVALID", "拷贝漫画封面地址无效");
        }
        List<String> authors = new ArrayList<>();
        for (JsonNode author : comic.path("author")) {
            String name = author.path("name").asText("").trim();
            if (!name.isBlank()) authors.add(name);
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : comic.path("theme")) {
            String name = tag.path("name").asText("").trim();
            if (!name.isBlank()) tags.add(name);
        }
        String status = comic.path("status").path("display").asText("").trim();
        if (status.isBlank()) status = comic.path("status").asText("").trim();
        String description = comic.path("brief").asText("").trim();
        return new MangaSource.Detail(new MangaSource.SearchItem("copymanga", mangaId,
                title, String.join(" / ", authors), cover.isBlank() ? null : cover, null),
                description, status, null, List.copyOf(tags), chapters);
    }

    private List<MangaSource.ChapterItem> chapters(String mangaId, JsonNode groups) {
        if (groups.size() > 100) throw invalidDirectory();
        List<MangaSource.ChapterItem> result = new ArrayList<>();
        var seenChapterIds = new HashSet<String>();
        var seenGroups = new HashSet<String>();
        for (JsonNode group : groups) {
            String groupPath = group.path("path_word").asText("");
            JsonNode countNode = group.path("count");
            if (!groupPath.matches("[a-zA-Z0-9_-]{1,128}") || !seenGroups.add(groupPath)
                    || !countNode.isIntegralNumber()) {
                throw invalidDirectory();
            }
            int count = countNode.asInt(-1);
            if (count < 0 || count > MAX_CHAPTERS) throw invalidDirectory();
            int offset = 0;
            while (offset < count) {
                JsonNode page = get("/comic/" + mangaId + "/group/" + groupPath
                        + "/chapters?platform=3&limit=" + PAGE_SIZE + "&offset=" + offset);
                if (page.path("code").asInt(-1) != 200) {
                    throw failure("COPYMANGA_UPSTREAM_REJECTED",
                            "拷贝漫画目录接口拒绝请求，请稍后重试");
                }
                JsonNode pageResults = page.path("results");
                JsonNode list = pageResults.path("list");
                if (!pageResults.isObject() || !pageResults.path("total").isIntegralNumber()
                        || pageResults.path("total").asInt(-1) != count
                        || !list.isArray() || list.isEmpty() || list.size() > PAGE_SIZE
                        || offset + list.size() > count
                        || result.size() + list.size() > MAX_CHAPTERS) {
                    throw invalidDirectory();
                }
                for (JsonNode entry : list) {
                    String chapterId = entry.path("uuid").asText("").toLowerCase(Locale.ROOT);
                    String name = entry.path("name").asText("").trim();
                    if (!CHAPTER_ID.matcher(chapterId).matches() || name.isBlank()
                            || !seenChapterIds.add(chapterId)) {
                        throw invalidDirectory();
                    }
                    result.add(new MangaSource.ChapterItem(mangaId + "~" + chapterId,
                            name, name, "zh", -1, false));
                }
                offset += list.size();
            }
        }
        return List.copyOf(result);
    }

    private JsonNode get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(api + path))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("version", VERSION)
                .header("versionCode", VERSION_CODE)
                .header("platform", "3")
                .header("source", "copyApp")
                .header("webp", "1")
                .header("region", "1")
                .header("referer", "com.copymanga.app-3.0.9")
                .GET().build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.limiting(
                    HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure("COPYMANGA_HTTP_INTERRUPTED", "拷贝漫画目录请求已中断");
        } catch (IOException error) {
            throw failure("COPYMANGA_HTTP_UNAVAILABLE", "拷贝漫画官网连接失败或响应超限，请稍后重试");
        }
        if (response.statusCode() == 404) {
            throw new ApiException(404, "COPYMANGA_NOT_FOUND", "拷贝漫画作品不存在");
        }
        if (response.statusCode() != 200) {
            throw failure("COPYMANGA_HTTP_UNAVAILABLE", "拷贝漫画官网暂时不可用，请稍后重试");
        }
        JsonNode body;
        try {
            body = mapper.readTree(new String(response.body(), StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            throw failure("COPYMANGA_UPSTREAM_INVALID", "拷贝漫画接口返回无法解析的数据");
        }
        if (body == null || !body.isObject()) throw invalidDirectory();
        return body;
    }

    private static boolean safeCover(String cover) {
        try {
            URI url = URI.create(cover);
            return "https".equals(url.getScheme()) && url.getHost() != null
                    && url.getUserInfo() == null && url.getFragment() == null
                    && (url.getPort() == -1 || url.getPort() == 443);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static ApiException invalidDirectory() {
        return failure("COPYMANGA_DIRECTORY_INVALID", "拷贝漫画目录完整性校验失败，未返回不完整目录");
    }

    private static ApiException failure(String code, String message) {
        return new ApiException(502, code, message);
    }
}
