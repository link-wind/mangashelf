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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 拷贝漫画章节图片（M29）：官方 v3 接口按章节返回全部图片地址。
 *
 * <p>匿名章节不需要 Cookie；接口同时返回 {@code chapter.size} 与
 * {@code chapter.contents}，两者不一致时直接失败，避免把不完整章节当成成功。</p>
 */
@Service
public class CopyMangaHttpReaderService {

    private static final String API = "https://api.manga2026.xyz/api/v3";
    private static final String USER_AGENT = "COPY/3.0.9";
    private static final String VERSION = "3.0.9";
    private static final String VERSION_CODE = "83";
    private static final Pattern MANGA_ID = Pattern.compile("[a-zA-Z0-9_-]{1,128}");
    private static final Pattern CHAPTER_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PAGES = 1000;
    private static final int MAX_CACHE_ENTRIES = 256;
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private record Cached(MangaSource.Pages pages, long expiresAt) {}

    private record ChapterKey(String pathWord, String uuid) {}

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI api;
    private final Clock clock;
    private final Map<String, Cached> cache = new LinkedHashMap<>();

    @Autowired
    public CopyMangaHttpReaderService(ObjectMapper mapper,
                                      @Value("${mangashelf.copymanga.proxy:}") String proxy) {
        this(mapper, createClient(proxy), URI.create(API), Clock.systemUTC());
    }

    CopyMangaHttpReaderService(ObjectMapper mapper, HttpClient client, URI api, Clock clock) {
        this.mapper = mapper;
        this.client = client;
        this.api = api;
        this.clock = clock;
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

    public MangaSource.Pages pages(String chapterId) {
        ChapterKey key = parse(chapterId);
        String cacheKey = key.pathWord() + "~" + key.uuid().toLowerCase(Locale.ROOT);
        synchronized (cache) {
            Cached hit = cache.get(cacheKey);
            if (hit != null && hit.expiresAt() > clock.millis()) return hit.pages();
            cache.remove(cacheKey);
        }
        MangaSource.Pages result = load(key);
        synchronized (cache) {
            cache.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= clock.millis());
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                String oldest = cache.entrySet().stream()
                        .min(Comparator.comparingLong(entry -> entry.getValue().expiresAt()))
                        .orElseThrow().getKey();
                cache.remove(oldest);
            }
            cache.put(cacheKey, new Cached(result, clock.millis() + CACHE_TTL.toMillis()));
        }
        return result;
    }

    private ChapterKey parse(String chapterId) {
        int sep = chapterId == null ? -1 : chapterId.indexOf('~');
        if (sep <= 0 || sep == chapterId.length() - 1) {
            throw invalidId();
        }
        String pathWord = chapterId.substring(0, sep);
        String uuid = chapterId.substring(sep + 1);
        if (!MANGA_ID.matcher(pathWord).matches() || !CHAPTER_ID.matcher(uuid).matches()) {
            throw invalidId();
        }
        return new ChapterKey(pathWord, uuid);
    }

    private MangaSource.Pages load(ChapterKey key) {
        String path = "/comic/" + key.pathWord() + "/chapter/" + key.uuid() + "?platform=3";
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
            throw failure("COPYMANGA_READER_INTERRUPTED", "拷贝漫画章节请求已中断");
        } catch (IOException error) {
            throw failure("COPYMANGA_READER_UNAVAILABLE", "拷贝漫画章节接口连接失败或响应超限，请稍后重试");
        }
        if (response.statusCode() == 404) {
            throw new ApiException(404, "COPYMANGA_NOT_FOUND", "拷贝漫画章节不存在");
        }
        if (response.statusCode() != 200) {
            throw failure("COPYMANGA_READER_UNAVAILABLE", "拷贝漫画章节接口暂时不可用，请稍后重试");
        }
        JsonNode body;
        try {
            body = mapper.readTree(new String(response.body(), StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            throw invalidChapter();
        }
        if (body == null || !body.isObject()) throw invalidChapter();

        int code = body.path("code").asInt(-1);
        if (code == 210) {
            throw failure("COPYMANGA_READER_RATE_LIMITED",
                    "拷贝漫画章节接口暂时拒绝请求（210），请稍后重试");
        }
        if (code != 200) {
            throw failure("COPYMANGA_READER_UNAVAILABLE", "拷贝漫画章节接口拒绝请求，请稍后重试");
        }
        JsonNode results = body.path("results");
        JsonNode chapter = results.path("chapter");
        if (!results.isObject() || !chapter.isObject()) throw invalidChapter();
        if (!key.pathWord().equals(chapter.path("comic_path_word").asText(""))
                || !key.uuid().equalsIgnoreCase(chapter.path("uuid").asText(""))) {
            throw invalidChapter();
        }

        JsonNode contents = chapter.path("contents");
        int size = chapter.path("size").asInt(-1);
        if (!contents.isArray() || contents.isEmpty()) {
            if (results.path("is_lock").asBoolean(false)) {
                throw new ApiException(403, "COPYMANGA_LOGIN_REQUIRED",
                        "该章节需要拷贝漫画登录态，请配置 Cookie / storage_state 后重试");
            }
            throw invalidChapter();
        }
        if (size < 1 || size > MAX_PAGES || contents.size() != size) {
            throw invalidChapter();
        }
        List<String> pages = new ArrayList<>(size);
        for (JsonNode item : contents) {
            String url = item.path("url").asText("").trim();
            if (!safeImageUrl(url)) throw invalidChapter();
            pages.add(url);
        }
        return new MangaSource.Pages(List.copyOf(pages), List.of(), false);
    }

    private static boolean safeImageUrl(String value) {
        try {
            URI url = URI.create(value);
            String host = url.getHost();
            if (!"https".equals(url.getScheme()) || host == null || url.getUserInfo() != null
                    || url.getFragment() != null
                    || (url.getPort() != -1 && url.getPort() != 443)) {
                return false;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return !lower.equals("localhost") && !lower.endsWith(".localhost")
                    && !lower.endsWith(".local") && !lower.startsWith("[")
                    && !lower.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static ApiException invalidId() {
        return new ApiException(400, "COPYMANGA_INVALID_ID", "章节 ID 应为 {pathWord}~{uuid}");
    }

    private static ApiException invalidChapter() {
        return failure("COPYMANGA_READER_INVALID",
                "拷贝漫画章节完整性校验失败，未返回完整页图");
    }

    private static ApiException failure(String code, String message) {
        return new ApiException(502, code, message);
    }
}
