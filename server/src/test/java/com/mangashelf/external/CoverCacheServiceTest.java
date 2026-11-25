package com.mangashelf.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 CoverCacheService 磁盘持久化缓存、单飞合并、失败负缓存与 SSRF 防护。 */
class CoverCacheServiceTest {

    @Test
    void coalescesConcurrentMissesAndServesFromDiskAfterward(@TempDir Path tmp) throws Exception {
        CoverCacheService cache = new CoverCacheService(tmp, null, null);
        AtomicInteger upstreamCalls = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        byte[] img = "webp-cover-payload".getBytes(StandardCharsets.UTF_8);

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<CoverCacheService.CoverData>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit((Callable<CoverCacheService.CoverData>) () -> {
                    gate.await();
                    return cache.getOrFetch("bangumi", "123-common", () -> {
                        upstreamCalls.incrementAndGet();
                        try {
                            Thread.sleep(60);
                        } catch (InterruptedException ignored) {
                        }
                        return new CoverCacheService.CoverData(img, "image/webp");
                    });
                }));
            }
            gate.countDown();
            for (Future<CoverCacheService.CoverData> f : futures) {
                CoverCacheService.CoverData got = f.get();
                assertNotNull(got);
                assertArrayEquals(img, got.bytes());
                assertEquals("image/webp", got.contentType());
            }
        }
        assertEquals(1, upstreamCalls.get(), "8 个并发请求应合并为 1 次上游回源");
        assertTrue(cache.isCached("bangumi", "123-common"));

        // 重建实例验证磁盘持久化命中（零上游回源）
        CoverCacheService reopened = new CoverCacheService(tmp, null, null);
        CoverCacheService.CoverData fromDisk = reopened.getOrFetch("bangumi", "123-common", () -> {
            upstreamCalls.incrementAndGet();
            return null;
        });
        assertNotNull(fromDisk);
        assertArrayEquals(img, fromDisk.bytes());
        assertEquals("image/webp", fromDisk.contentType());
        assertEquals(1, upstreamCalls.get(), "重启后应直接命中磁盘缓存");
    }

    @Test
    void cachesFailuresBrieflyAndRejectsPrivateAddresses(@TempDir Path tmp) {
        CoverCacheService cache = new CoverCacheService(tmp, null, null);
        AtomicInteger calls = new AtomicInteger();
        assertNull(cache.getOrFetch("catalog", "dead-item", () -> {
            calls.incrementAndGet();
            return null;
        }));
        assertNull(cache.getOrFetch("catalog", "dead-item", () -> {
            calls.incrementAndGet();
            return null;
        }));
        assertEquals(1, calls.get(), "失败条目应在负缓存窗口内短路返回");

        assertFalse(CoverCacheService.isSafePublicHttpUri(URI.create("http://127.0.0.1:9000/secret")));
        assertFalse(CoverCacheService.isSafePublicHttpUri(URI.create("http://localhost:8080/api")));
        assertFalse(CoverCacheService.isSafePublicHttpUri(URI.create("http://192.168.1.1/a.jpg")));
        assertFalse(CoverCacheService.isSafePublicHttpUri(URI.create("http://169.254.169.254/latest")));
        assertFalse(CoverCacheService.isSafePublicHttpUri(URI.create("file:///etc/passwd")));

        assertEquals(
                "https://p.lztutu.com/upload/vod/20261015-1/a.webp",
                CoverCacheService.fallbackCatalogImageUrl("https://p.lzp261118.com/upload/vod/20261015-1/a.webp"));
        assertNull(CoverCacheService.fallbackCatalogImageUrl("https://p.lztutu.com/upload/vod/a.webp"));
    }
}
