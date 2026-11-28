package com.mangashelf.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量播放直链同步快解器：
 * 1. 本身即 .m3u8/.mp4 的剧集地址（量子资源等）：零网络开销直接返回；
 * 2. 标准 MacCMS 播放页（player_aaaa encrypt=0/1/2，如 7sefun 七色B线）：
 *    在 API 层直接通过 Java HttpClient 解码并单跳 HEAD 取 302 Location，省去 Outbox/MQ/Python 进程开销；
 * 3. 非直链或复杂加密播放器：返回 empty，交由现有异步 Worker + Playwright 嗅探处理。
 */
final class FastPlaybackResolver {

    private static final Logger log = LoggerFactory.getLogger(FastPlaybackResolver.class);
    private static final Duration FAST_TIMEOUT = Duration.ofSeconds(6);
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private static final Pattern VIDEO_EXT = Pattern.compile("\\.(m3u8|mp4)(\\?|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYER_AAAA = Pattern.compile(
            "player_aaaa\\s*=\\s*(\\{.*?\\})\\s*</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final HttpClient NO_REDIRECT_CLIENT = HttpClient.newBuilder()
            .connectTimeout(FAST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final HttpClient PAGE_CLIENT = HttpClient.newBuilder()
            .connectTimeout(FAST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record ResolvedMedia(String playUrl, String playKind) {}

    private FastPlaybackResolver() {}

    /** 若地址本身即为 http(s) 的 .m3u8 / .mp4 直链，返回 "hls" 或 "mp4"，否则返回 null。 */
    static String directVideoKind(String url) {
        if (url == null || url.isBlank()) return null;
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null;
        String noQuery = trimmed.split("\\?", 2)[0];
        if (!VIDEO_EXT.matcher(noQuery).find()) return null;
        return noQuery.toLowerCase().contains(".m3u8") ? "hls" : "mp4";
    }

    /** 从 MacCMS 播放页 HTML 中提取并解码 player_aaaa.url。 */
    static String extractMacCmsPlayerUrl(String html) {
        if (html == null || html.isBlank()) return null;
        Matcher m = PLAYER_AAAA.matcher(html);
        if (!m.find()) return null;
        try {
            JsonNode node = MAPPER.readTree(m.group(1));
            String raw = node.path("url").asText("").trim();
            if (raw.isEmpty()) return null;
            int encrypt = node.path("encrypt").asInt(0);
            if (encrypt == 2) {
                byte[] decoded = Base64.getDecoder().decode(raw);
                raw = URLDecoder.decode(new String(decoded, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            } else if (encrypt == 1) {
                raw = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            }
            return raw.trim().isEmpty() ? null : raw.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** 尝试纯 HTTP 同步解码 MacCMS 播放页，仅在命中真实视频直链时返回。 */
    static Optional<ResolvedMedia> tryResolveMacCms(String episodeUrl, String referer, String cookie) {
        if (episodeUrl == null || episodeUrl.isBlank()) return Optional.empty();
        String direct = directVideoKind(episodeUrl);
        if (direct != null) {
            return Optional.of(new ResolvedMedia(episodeUrl.trim(), direct));
        }
        String html = fetchPageHtml(episodeUrl, referer, cookie);
        if (html == null) return Optional.empty();

        String decoded = extractMacCmsPlayerUrl(html);
        String kind = directVideoKind(decoded);
        if (decoded == null || kind == null) {
            return Optional.empty();
        }

        String finalUrl = probeSingleRedirect(decoded);
        String finalKind = directVideoKind(finalUrl);
        return Optional.of(new ResolvedMedia(finalUrl, finalKind != null ? finalKind : kind));
    }

    private static String fetchPageHtml(String url, String referer, String cookie) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return null;
        }
        var builder = HttpRequest.newBuilder(uri)
                .timeout(FAST_TIMEOUT)
                .header("User-Agent", DEFAULT_UA)
                .GET();
        if (referer != null && !referer.isBlank()) builder.header("Referer", referer);
        if (cookie != null && !cookie.isBlank()) builder.header("Cookie", cookie);
        HttpRequest req = builder.build();

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpResponse<String> resp = PAGE_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) return null;
                return resp.body();
            } catch (IOException e) {
                if (attempt < 2) {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    log.debug("fast resolve page fetch failed for {}: {}", url, e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** 发一次不跟随重定向的 HEAD 请求，若 301/302 Location 仍是视频地址则返回该直链。 */
    private static String probeSingleRedirect(String url) {
        try {
            URI base = URI.create(url);
            HttpRequest req = HttpRequest.newBuilder(base)
                    .timeout(FAST_TIMEOUT)
                    .header("User-Agent", DEFAULT_UA)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> resp = NO_REDIRECT_CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
            int code = resp.statusCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = resp.headers().firstValue("Location").orElse("");
                if (!loc.isBlank()) {
                    String resolved = base.resolve(loc.trim()).toString();
                    if (directVideoKind(resolved) != null) {
                        return resolved;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // HEAD 探测失败时回退使用解码出的原直链
        }
        return url;
    }
}
