package com.mangashelf.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 热点缓存单测：TTL、主动失效、击穿并发合并、关闭开关透传与 Micrometer 指标。 */
class HotspotCacheTest {

    @Test
    void concurrentMissesCoalesceIntoSingleBackendLoad() throws Exception {
        var registry = new SimpleMeterRegistry();
        var cache = new HotspotCache(true, 64, registry);
        AtomicInteger loads = new AtomicInteger(0);
        CountDownLatch insideLoader = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return cache.getOrLoad("calendar", "calendar", Duration.ofSeconds(30), () -> {
                        int n = loads.incrementAndGet();
                        insideLoader.countDown();
                        try { releaseLoader.await(); } catch (InterruptedException ignored) {}
                        return "snapshot-" + n;
                    });
                }));
            }
            ready.await();
            start.countDown();
            insideLoader.await();
            Thread.sleep(30);
            releaseLoader.countDown();

            for (Future<String> f : futures) {
                assertEquals("snapshot-1", f.get());
            }
        }
        assertEquals(1, loads.get(), "高并发同 Key 穿透时必须合并为单次回源");
        assertEquals(1.0, registry.get("mangashelf.hotspot.cache.requests")
                .tag("name", "calendar").tag("result", "miss").counter().count());
        assertEquals(threads - 1.0, registry.get("mangashelf.hotspot.cache.requests")
                .tag("name", "calendar").tag("result", "hit").counter().count());
    }

    @Test
    void invalidatePrefixAndDisabledModeWorkAsExpected() {
        var cache = new HotspotCache(true, 16);
        AtomicInteger seq = new AtomicInteger(0);
        assertEquals(1, cache.getOrLoad("cat", "catalog-page:1", Duration.ofSeconds(30), seq::incrementAndGet));
        assertEquals(1, cache.getOrLoad("cat", "catalog-page:1", Duration.ofSeconds(30), seq::incrementAndGet));
        cache.invalidatePrefix("catalog-");
        assertEquals(2, cache.getOrLoad("cat", "catalog-page:1", Duration.ofSeconds(30), seq::incrementAndGet));

        var disabled = new HotspotCache(false, 16);
        assertEquals(3, disabled.getOrLoad("cat", "k", Duration.ofSeconds(30), seq::incrementAndGet));
        assertEquals(4, disabled.getOrLoad("cat", "k", Duration.ofSeconds(30), seq::incrementAndGet));
    }
}
