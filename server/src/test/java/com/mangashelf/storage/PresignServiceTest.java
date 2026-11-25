package com.mangashelf.storage;

import com.mangashelf.config.MangaShelfProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 PresignService 分层 Cache-Control 参数与 TTL 前半窗口签名复用行为。 */
class PresignServiceTest {

    private static MangaShelfProperties props(int ttlSeconds) {
        return new MangaShelfProperties(
                UUID.randomUUID(),
                "test-platform",
                "secret",
                new MangaShelfProperties.Storage(
                        "http://127.0.0.1:9000", "us-east-1", "mangashelf",
                        "mangashelf", "mangashelf-secret", true, ttlSeconds),
                null, null, null, null, null);
    }

    private static S3Presigner localPresigner() {
        return S3Presigner.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create("http://127.0.0.1:9000"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("mangashelf", "mangashelf-secret")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private static Clock mutableClock(AtomicReference<Instant> ref) {
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return ref.get();
            }
        };
    }

    @Test
    void reusesUrlWithinFirstHalfWindowAndRefreshesAfterHalfTtl() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-25T00:00:00Z"));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try (S3Presigner signer = localPresigner()) {
            PresignService svc = new PresignService(signer, props(600), true, mutableClock(now), metrics);

            PresignService.PresignedUrl first = svc.presignGet(
                    "chapters/r1/pages/p1/reader.webp", PresignService.MediaCacheProfile.IMMUTABLE_IMAGE);
            assertTrue(first.url().contains("response-cache-control=private%2C%20max-age%3D86400%2C%20immutable"),
                    "URL 应携带分层 Cache-Control 参数：" + first.url());

            // 120s 后仍在 TTL(600s) 前半段（剩余 480s >= 300s），应复用完全一致的 URL
            now.set(Instant.parse("2026-11-25T00:02:00Z"));
            PresignService.PresignedUrl second = svc.presignGet(
                    "chapters/r1/pages/p1/reader.webp", PresignService.MediaCacheProfile.IMMUTABLE_IMAGE);
            assertEquals(first.url(), second.url());
            assertEquals(first.expiresAt(), second.expiresAt());

            // 301s 后剩余 299s < 300s，必须刷新签发新 URL
            now.set(Instant.parse("2026-11-25T00:05:01Z"));
            PresignService.PresignedUrl refreshed = svc.presignGet(
                    "chapters/r1/pages/p1/reader.webp", PresignService.MediaCacheProfile.IMMUTABLE_IMAGE);
            assertNotEquals(first.expiresAt(), refreshed.expiresAt());

            assertEquals(2.0, metrics.counter("mangashelf.media.presign.requests",
                    "profile", "immutable_image", "result", "sign").count());
            assertEquals(1.0, metrics.counter("mangashelf.media.presign.requests",
                    "profile", "immutable_image", "result", "reuse").count());
        }
    }

    @Test
    void respectsReuseDisabledToggleAndSeparatesProfiles() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-25T00:00:00Z"));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try (S3Presigner signer = localPresigner()) {
            PresignService disabled = new PresignService(signer, props(600), false, mutableClock(now), metrics);
            PresignService.PresignedUrl u1 = disabled.presignGet(
                    "animations/r1/video.mp4", PresignService.MediaCacheProfile.IMMUTABLE_VIDEO);
            now.set(Instant.parse("2026-11-25T00:00:05Z"));
            PresignService.PresignedUrl u2 = disabled.presignGet(
                    "animations/r1/video.mp4", PresignService.MediaCacheProfile.IMMUTABLE_VIDEO);
            assertNotEquals(u1.expiresAt(), u2.expiresAt());
            assertTrue(u1.url().contains("response-cache-control=private%2C%20max-age%3D86400"));
            assertEquals(2.0, metrics.counter("mangashelf.media.presign.requests",
                    "profile", "immutable_video", "result", "sign").count());
        }
    }
}
