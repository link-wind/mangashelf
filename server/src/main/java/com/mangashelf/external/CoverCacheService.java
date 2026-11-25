package com.mangashelf.external;

import com.mangashelf.config.MangaShelfProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 外部封面持久化磁盘缓存（Bangumi + 目录条目）：
 * 1) 磁盘命中直接返回（零上游网络请求）；
 * 2) 同 Key 单飞合并（CompletableFuture），防止首页高并发穿透上游；
 * 3) 失败短时负缓存（60s），避免反复阻塞在死链或超时图床上；
 * 4) 仅按库内 ID 拉取白名单公开 http/https 图片，拒绝任意 URL 与私网地址（DESIGN 19 防 SSRF）。
 */
@Service
public class CoverCacheService {

    private static final Logger log = LoggerFactory.getLogger(CoverCacheService.class);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(60);
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final String UA = "Mozilla/5.0 (compatible; MangaShelf/1.0)";

    public record CoverData(byte[] bytes, String contentType) {}

    private final Path cacheRoot;
    private final JdbcTemplate jdbc;
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, CompletableFuture<CoverData>> inflight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> negativeCache = new ConcurrentHashMap<>();

    @Autowired
    public CoverCacheService(MangaShelfProperties props, JdbcTemplate jdbc) {
        this(resolveCacheRoot(props), jdbc, HttpClient.newBuilder()
                .connectTimeout(FETCH_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    CoverCacheService(Path cacheRoot, JdbcTemplate jdbc, HttpClient httpClient) {
        this.cacheRoot = cacheRoot;
        this.jdbc = jdbc;
        this.httpClient = httpClient;
    }

    private static Path resolveCacheRoot(MangaShelfProperties props) {
        if (props != null && props.processing() != null && props.processing().workRoot() != null) {
            Path workRoot = Path.of(props.processing().workRoot()).toAbsolutePath().normalize();
            Path parent = workRoot.getParent();
            if (parent != null) {
                return parent.resolve("covers");
            }
        }
        return Path.of("./data/covers").toAbsolutePath().normalize();
    }

    public boolean isCached(String bucket, String key) {
        return Files.isRegularFile(dataPath(bucket, key));
    }

    /** 统一读盘 → 负缓存 → 单飞回源 → 原子落盘。 */
    public CoverData getOrFetch(String bucket, String key, Supplier<CoverData> remoteFetcher) {
        String cacheKey = bucket + ":" + key;
        CoverData onDisk = readFromDisk(bucket, key);
        if (onDisk != null) {
            return onDisk;
        }
        Instant negUntil = negativeCache.get(cacheKey);
        if (negUntil != null) {
            if (Instant.now().isBefore(negUntil)) {
                return null;
            }
            negativeCache.remove(cacheKey, negUntil);
        }

        CompletableFuture<CoverData> created = new CompletableFuture<>();
        CompletableFuture<CoverData> existing = inflight.putIfAbsent(cacheKey, created);
        if (existing != null) {
            return existing.join();
        }
        try {
            // 双重检查：排队期间可能已落盘
            CoverData recheck = readFromDisk(bucket, key);
            if (recheck != null) {
                created.complete(recheck);
                return recheck;
            }
            CoverData fetched = remoteFetcher.get();
            if (fetched != null && fetched.bytes() != null && fetched.bytes().length > 0) {
                writeToDiskAtomic(bucket, key, fetched);
                negativeCache.remove(cacheKey);
            } else {
                markNegative(cacheKey);
            }
            created.complete(fetched);
            return fetched;
        } catch (Exception e) {
            log.warn("cover fetch failed for {}: {}", cacheKey, e.getMessage());
            markNegative(cacheKey);
            created.complete(null);
            return null;
        } finally {
            inflight.remove(cacheKey, created);
        }
    }

    /** 目录条目封面代理：仅从 external_catalog_items 查 cover_url，不接收任意 URL。 */
    public CoverData fetchCatalogCover(UUID itemId) {
        return getOrFetch("catalog", itemId.toString(), () -> {
            List<String> urls = jdbc.query(
                    "SELECT cover_url FROM external_catalog_items WHERE id = ? AND cover_url <> ''",
                    (rs, i) -> rs.getString("cover_url"), itemId);
            if (urls.isEmpty()) {
                return null;
            }
            String primary = urls.get(0);
            CoverData data = downloadPublicImage(primary);
            if (data != null) {
                return data;
            }
            // 资源站临时日期图床域名（如 p.lzp261118.com）过期下线后，同路径在主图床 p.lztutu.com 仍可读
            String fallback = fallbackCatalogImageUrl(primary);
            return fallback == null ? null : downloadPublicImage(fallback);
        });
    }

    static String fallbackCatalogImageUrl(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.matches("(?i)^https?://p\\.lzp\\d+\\.com/.+")) {
            return trimmed.replaceFirst("(?i)^https?://p\\.lzp\\d+\\.com", "https://p.lztutu.com");
        }
        return null;
    }

    CoverData downloadPublicImage(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            return null;
        }
        if (!isSafePublicHttpUri(uri)) {
            log.warn("rejected unsafe cover url host={}", uri.getHost());
            return null;
        }
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(FETCH_TIMEOUT)
                .header("User-Agent", UA)
                .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                .GET()
                .build();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = resp.body()) {
                    if (resp.statusCode() != 200) {
                        return null;
                    }
                    String ct = resp.headers().firstValue("content-type").orElse("image/jpeg");
                    String mediaType = ct.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                    if (!mediaType.startsWith("image/")) {
                        return null;
                    }
                    byte[] bytes = readBounded(in, MAX_IMAGE_BYTES);
                    if (bytes == null || bytes.length == 0) {
                        return null;
                    }
                    return new CoverData(bytes, mediaType);
                }
            } catch (Exception e) {
                log.debug("catalog cover download attempt {} failed ({}): {}", attempt, uri.getHost(), e.getMessage());
                if (attempt < 2) {
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        return null;
    }

    static boolean isSafePublicHttpUri(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ("localhost".equals(host) || host.endsWith(".local") || host.endsWith(".internal")) {
            return false;
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress addr : addrs) {
                if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                        || addr.isMulticastAddress()) {
                    return false;
                }
            }
            return addrs.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        byte[] buf = new byte[16384];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > maxBytes) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private Path dataPath(String bucket, String key) {
        String safeKey = key.replaceAll("[^a-zA-Z0-9._-]", "_");
        return cacheRoot.resolve(bucket).resolve(safeKey + ".bin");
    }

    private Path metaPath(String bucket, String key) {
        String safeKey = key.replaceAll("[^a-zA-Z0-9._-]", "_");
        return cacheRoot.resolve(bucket).resolve(safeKey + ".ct");
    }

    private CoverData readFromDisk(String bucket, String key) {
        Path file = dataPath(bucket, key);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return null;
            }
            Path ctFile = metaPath(bucket, key);
            String ct = Files.isRegularFile(ctFile)
                    ? Files.readString(ctFile, StandardCharsets.UTF_8).trim()
                    : "image/jpeg";
            if (ct.isBlank()) {
                ct = "image/jpeg";
            }
            return new CoverData(bytes, ct);
        } catch (IOException e) {
            return null;
        }
    }

    private void writeToDiskAtomic(String bucket, String key, CoverData data) {
        try {
            Path file = dataPath(bucket, key);
            Path dir = file.getParent();
            Files.createDirectories(dir);

            Path tmpData = dir.resolve(file.getFileName() + ".tmp." + UUID.randomUUID());
            Files.write(tmpData, data.bytes());
            moveAtomic(tmpData, file);

            Path ctFile = metaPath(bucket, key);
            Path tmpCt = dir.resolve(ctFile.getFileName() + ".tmp." + UUID.randomUUID());
            Files.writeString(tmpCt, data.contentType() == null ? "image/jpeg" : data.contentType(), StandardCharsets.UTF_8);
            moveAtomic(tmpCt, ctFile);
        } catch (Exception e) {
            log.warn("cover cache write failed for {}/{}: {}", bucket, key, e.getMessage());
        }
    }

    private static void moveAtomic(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void markNegative(String cacheKey) {
        if (negativeCache.size() > 2048) {
            Instant now = Instant.now();
            negativeCache.entrySet().removeIf(e -> !now.isBefore(e.getValue()));
            if (negativeCache.size() > 2048) {
                negativeCache.clear();
            }
        }
        negativeCache.put(cacheKey, Instant.now().plus(NEGATIVE_TTL));
    }
}
