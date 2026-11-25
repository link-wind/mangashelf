package com.mangashelf.storage;

import com.mangashelf.config.MangaShelfProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短期签名授权（DESIGN 10.2 / 14.1 / 14.2 / 18.1）；签名地址不写入应用日志。
 * 所有调用方必须在完成实时权限与可见性检查后再调用本服务；通过后在 TTL 前半窗口内
 * 复用同一签名 URL，确保浏览器缓存、ETag/304 与分层 Cache-Control 生效。
 */
@Service
public class PresignService {

    private static final int MAX_CACHE_ENTRIES = 2048;

    /** 分层媒体缓存策略：所有不可变派生资产按路径版本隔离，使用 private 避免共享代理越权缓存。 */
    public enum MediaCacheProfile {
        IMMUTABLE_IMAGE("private, max-age=86400, immutable"),
        IMMUTABLE_VIDEO("private, max-age=86400"),
        IMMUTABLE_SUBTITLE("private, max-age=86400, immutable"),
        DEFAULT("private, max-age=300");

        private final String headerValue;

        MediaCacheProfile(String headerValue) {
            this.headerValue = headerValue;
        }

        public String headerValue() {
            return headerValue;
        }
    }

    public record PresignedUrl(String url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    private record CacheKey(String objectKey, MediaCacheProfile profile) {
    }

    private final S3Presigner presigner;
    private final MangaShelfProperties props;
    private final boolean reuseEnabled;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<CacheKey, PresignedUrl> urlCache = new ConcurrentHashMap<>();

    @Autowired
    public PresignService(S3Presigner presigner,
                          MangaShelfProperties props,
                          @Value("${mangashelf.storage.presign-reuse-enabled:true}") boolean reuseEnabled,
                          ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(presigner, props, reuseEnabled, Clock.systemUTC(), meterRegistryProvider.getIfAvailable());
    }

    PresignService(S3Presigner presigner,
                   MangaShelfProperties props,
                   boolean reuseEnabled,
                   Clock clock,
                   MeterRegistry meterRegistry) {
        this.presigner = presigner;
        this.props = props;
        this.reuseEnabled = reuseEnabled;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    public PresignedUrl presignPut(String objectKey, String contentType) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(props.storage().bucket())
                .key(objectKey)
                .contentType(contentType)
                .build();
        var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(props.storage().presignTtlSeconds()))
                .putObjectRequest(put)
                .build());
        return toResult(presigned.url().toString(), "PUT", flatten(presigned.signedHeaders()), clock.instant());
    }

    public PresignedUrl presignGet(String objectKey) {
        return presignGet(objectKey, MediaCacheProfile.DEFAULT);
    }

    public PresignedUrl presignGet(String objectKey, MediaCacheProfile profile) {
        MediaCacheProfile effectiveProfile = profile == null ? MediaCacheProfile.DEFAULT : profile;
        Instant now = clock.instant();
        int ttlSeconds = Math.max(1, props.storage().presignTtlSeconds());

        if (reuseEnabled) {
            CacheKey key = new CacheKey(objectKey, effectiveProfile);
            Duration minRemaining = Duration.ofSeconds(Math.max(1, ttlSeconds / 2));
            PresignedUrl cached = urlCache.get(key);
            if (cached != null && !cached.expiresAt().isBefore(now.plus(minRemaining))) {
                recordMetric(effectiveProfile, "reuse");
                return cached;
            }
            PresignedUrl fresh = signGet(objectKey, effectiveProfile, ttlSeconds, now);
            putBounded(key, fresh, now);
            recordMetric(effectiveProfile, "sign");
            return fresh;
        }

        PresignedUrl fresh = signGet(objectKey, effectiveProfile, ttlSeconds, now);
        recordMetric(effectiveProfile, "sign");
        return fresh;
    }

    public void clearCache() {
        urlCache.clear();
    }

    private PresignedUrl signGet(String objectKey, MediaCacheProfile profile, int ttlSeconds, Instant now) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(props.storage().bucket())
                .key(objectKey)
                .responseCacheControl(profile.headerValue())
                .build();
        var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(ttlSeconds))
                .getObjectRequest(get)
                .build());
        return toResult(presigned.url().toString(), "GET", flatten(presigned.signedHeaders()), now);
    }

    private void putBounded(CacheKey key, PresignedUrl value, Instant now) {
        if (urlCache.size() >= MAX_CACHE_ENTRIES) {
            urlCache.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(now));
            if (urlCache.size() >= MAX_CACHE_ENTRIES) {
                urlCache.clear();
            }
        }
        urlCache.put(key, value);
    }

    private void recordMetric(MediaCacheProfile profile, String result) {
        if (meterRegistry != null) {
            meterRegistry.counter("mangashelf.media.presign.requests",
                    "profile", profile.name().toLowerCase(),
                    "result", result).increment();
        }
    }

    private PresignedUrl toResult(String url, String method, Map<String, String> signedHeaders, Instant now) {
        return new PresignedUrl(url, method, Map.copyOf(signedHeaders),
                now.plusSeconds(props.storage().presignTtlSeconds()));
    }

    /** SDK 返回 Map&lt;String, List&lt;String&gt;&gt;；展平为单值表便于客户端携带。 */
    private Map<String, String> flatten(Map<String, List<String>> signedHeaders) {
        return signedHeaders.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, e -> String.join(",", e.getValue())));
    }
}
