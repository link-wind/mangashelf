package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class BilibiliHttpClient {

    static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final String QUERY = "?device=android&platform=android&mobi_app=android_comic"
            + "&version=4.13.0&build=36413002&channel=bilicomic";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 12) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI apiRoot;
    private final URI fingerprintUrl;
    private String anonymousCookie;
    private long fingerprintExpiresAt;

    @Autowired
    public BilibiliHttpClient(ObjectMapper mapper,
                              @Value("${mangashelf.bilibili.proxy:}") String proxy) {
        this(mapper, createClient(proxy), URI.create("https://manga.bilibili.com"),
                URI.create("https://api.bilibili.com/x/frontend/finger/spi"));
    }

    BilibiliHttpClient(ObjectMapper mapper, HttpClient client, URI apiRoot, URI fingerprintUrl) {
        this.mapper = mapper;
        this.client = client;
        this.apiRoot = apiRoot;
        this.fingerprintUrl = fingerprintUrl;
    }

    private static HttpClient createClient(String proxy) {
        ProxySelector selector;
        if (proxy == null || proxy.isBlank()) {
            selector = new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    return List.of(Proxy.NO_PROXY);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress address, IOException error) {
                }
            };
        } else {
            URI target = URI.create(proxy);
            if (!"http".equals(target.getScheme()) || target.getHost() == null
                    || target.getUserInfo() != null || target.getPort() < 1) {
                throw new IllegalArgumentException("哔哩 HTTP 代理必须是带端口且不含凭据的 http 地址");
            }
            selector = ProxySelector.of(new InetSocketAddress(target.getHost(), target.getPort()));
        }
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).proxy(selector).build();
    }

    public JsonNode detail(String mangaId) {
        return call("ComicDetail", Map.of("comicId", Long.parseLong(mangaId)));
    }

    public JsonNode hot() {
        return call("HomeHot", Map.of());
    }

    /** 分类翻页（ClassPage）：styleId=-1 表示全站，areaId: -1 全部 / 2 日本 / 1 大陆 / 6 韩国 / 5 其他，order=0 按人气排序。 */
    public JsonNode classPage(int styleId, int pageNum, int pageSize) {
        return classPage(styleId, -1, pageNum, pageSize);
    }

    public JsonNode classPage(int styleId, int areaId, int pageNum, int pageSize) {
        return call("ClassPage", Map.of(
                "style_id", styleId,
                "area_id", areaId,
                "is_finish", -1,
                "order", 0,
                "page_num", pageNum,
                "page_size", pageSize,
                "is_free", -1));
    }

    public JsonNode index(String episodeId) {
        return call("GetImageIndex", Map.of("epId", Long.parseLong(episodeId)));
    }

    public byte[] image(String path) {
        for (int attempt = 0; attempt < 2; attempt++) {
            JsonNode tokens = call("ImageToken", Map.of("urls", mapper.writeValueAsString(List.of(path))));
            if (!tokens.isArray() || tokens.size() != 1) {
                throw failure("BILIBILI_IMAGE_TOKEN_INVALID", "哔哩页图地址未能确认");
            }
            String complete = tokens.get(0).path("complete_url").asText("");
            URI target;
            try {
                target = URI.create(complete);
            } catch (IllegalArgumentException error) {
                throw failure("BILIBILI_IMAGE_URL_INVALID", "哔哩页图地址无效");
            }
            if (!isAllowedImageUrl(target)) {
                throw failure("BILIBILI_IMAGE_URL_INVALID", "哔哩页图地址不在允许的来源内");
            }
            HttpResponse<byte[]> response = exchange(request(target).GET().build(), MAX_IMAGE_BYTES);
            if ((response.statusCode() == 401 || response.statusCode() == 403) && attempt == 0) {
                continue;
            }
            requireSuccess(response.statusCode(), "哔哩页图下载失败");
            return response.body();
        }
        throw failure("BILIBILI_IMAGE_UNAVAILABLE", "哔哩页图下载失败");
    }

    static boolean isAllowedImageUrl(URI target) {
        String host = target.getHost();
        return "https".equals(target.getScheme()) && target.getUserInfo() == null
                && (target.getPort() == -1 || target.getPort() == 443)
                && target.getFragment() == null && host != null
                && (host.endsWith(".hdslb.com") || host.endsWith(".bcomic.com"));
    }

    private JsonNode call(String method, Map<String, Object> payload) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String cookie = cookie();
            HttpRequest request = request(apiRoot.resolve("/twirp/comic.v1.Comic/" + method + QUERY))
                    .header("Content-Type", "application/json")
                    .header("Cookie", cookie)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
            HttpResponse<byte[]> response = exchange(request, 8 * 1024 * 1024);
            requireSuccess(response.statusCode(), "哔哩接口请求失败");
            JsonNode result = parse(response.body());
            if (result.path("code").asInt(-1) == 99 && attempt == 0) {
                synchronized (this) {
                    if (cookie.equals(anonymousCookie)) anonymousCookie = null;
                }
                continue;
            }
            if (result.path("code").asInt(-1) != 0 || result.path("data").isNull()
                    || result.path("data").isMissingNode()) {
                throw failure("BILIBILI_API_UNAVAILABLE", "哔哩接口未返回可读取的数据，请稍后重试");
            }
            return result.path("data");
        }
        throw failure("BILIBILI_API_UNAVAILABLE", "哔哩接口暂时不可用");
    }

    private synchronized String cookie() {
        if (anonymousCookie != null && fingerprintExpiresAt > System.currentTimeMillis()) {
            return anonymousCookie;
        }
        HttpResponse<byte[]> response = exchange(request(fingerprintUrl).GET().build(), 64 * 1024);
        requireSuccess(response.statusCode(), "哔哩匿名访问初始化失败");
        JsonNode result = parse(response.body());
        String first = result.path("data").path("b_3").asText("");
        String second = result.path("data").path("b_4").asText("");
        if (result.path("code").asInt(-1) != 0 || !first.matches("[A-Za-z0-9_+/=%-]{1,256}")
                || !second.matches("[A-Za-z0-9_+/=%-]{1,256}")) {
            throw failure("BILIBILI_FINGERPRINT_INVALID", "哔哩匿名访问初始化失败");
        }
        anonymousCookie = "buvid3=" + first + "; buvid4=" + second;
        fingerprintExpiresAt = System.currentTimeMillis() + Duration.ofHours(6).toMillis();
        return anonymousCookie;
    }

    private HttpRequest.Builder request(URI target) {
        return HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://manga.bilibili.com/");
    }

    private HttpResponse<byte[]> exchange(HttpRequest request, int maxBytes) {
        try {
            return client.send(request,
                    HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), maxBytes));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure("BILIBILI_REQUEST_INTERRUPTED", "哔哩请求已中断");
        } catch (IOException error) {
            throw failure("BILIBILI_NETWORK_ERROR", "哔哩网络请求失败或响应超出限制");
        }
    }

    private JsonNode parse(byte[] body) {
        try {
            return mapper.readTree(body);
        } catch (RuntimeException error) {
            throw failure("BILIBILI_RESPONSE_INVALID", "哔哩接口返回格式无效");
        }
    }

    private static void requireSuccess(int status, String message) {
        if (status != 200) throw failure("BILIBILI_HTTP_ERROR", message + "（HTTP " + status + "）");
    }

    private static ApiException failure(String code, String message) {
        return new ApiException(502, code, message);
    }
}
