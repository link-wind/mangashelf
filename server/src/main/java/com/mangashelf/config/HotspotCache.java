package com.mangashelf.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 轻量进程内热点只读缓存（benchmark-db-and-hotspot-cache）：
 * - 有界容量（默认 256 槽）、显式 TTL、同 Key 并发回源合并（防缓存击穿）
 * - 支持主动失效（invalidate / invalidatePrefix）与开关控制（用于压测 A/B 对照）
 * - 暴露 Micrometer 指标 mangashelf.hotspot.cache.requests{name,result=hit|miss}
 */
@Component
public class HotspotCache {

    private static final int DEFAULT_MAX_ENTRIES = 256;

    private record Entry(Object value, long expiresAtNanos) {
        boolean alive(long now) {
            return now < expiresAtNanos;
        }
    }

    private final boolean enabled;
    private final int maxEntries;
    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    @Autowired
    public HotspotCache(
            @Value("${mangashelf.hotspot-cache.enabled:true}") boolean enabled,
            @Value("${mangashelf.hotspot-cache.max-entries:256}") int maxEntries,
            MeterRegistry registry) {
        this.enabled = enabled;
        this.maxEntries = Math.max(16, maxEntries);
        this.registry = registry;
    }

    HotspotCache(boolean enabled, int maxEntries) {
        this(enabled, maxEntries, null);
    }

    public boolean isEnabled() {
        return enabled;
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String metricName, String key, Duration ttl, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        long now = System.nanoTime();
        Entry hit = store.get(key);
        if (hit != null && hit.alive(now)) {
            record(metricName, "hit");
            return (T) hit.value();
        }

        // 单 Key 并发回源合并：高并发下缓存过期瞬间只放行一个线程回源 DB，避免击穿
        CompletableFuture<Object> created = new CompletableFuture<>();
        CompletableFuture<Object> existing = inflight.putIfAbsent(key, created);
        if (existing != null) {
            try {
                Object value = existing.join();
                record(metricName, "hit");
                return (T) value;
            } catch (RuntimeException e) {
                throw e;
            }
        }

        record(metricName, "miss");
        try {
            T loaded = loader.get();
            if (store.size() >= maxEntries) {
                evictExpiredOrOldest(System.nanoTime());
            }
            store.put(key, new Entry(loaded, System.nanoTime() + ttl.toNanos()));
            created.complete(loaded);
            return loaded;
        } catch (Throwable t) {
            created.completeExceptionally(t);
            if (t instanceof RuntimeException re) throw re;
            throw new IllegalStateException(t);
        } finally {
            inflight.remove(key, created);
        }
    }

    public void invalidate(String key) {
        store.remove(key);
    }

    public void invalidatePrefix(String prefix) {
        store.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void clear() {
        store.clear();
    }

    public int size() {
        return store.size();
    }

    private void evictExpiredOrOldest(long now) {
        store.entrySet().removeIf(e -> !e.getValue().alive(now));
        if (store.size() >= maxEntries) {
            String oldestKey = null;
            long oldestExpiry = Long.MAX_VALUE;
            for (var e : store.entrySet()) {
                if (e.getValue().expiresAtNanos() < oldestExpiry) {
                    oldestExpiry = e.getValue().expiresAtNanos();
                    oldestKey = e.getKey();
                }
            }
            if (oldestKey != null) {
                store.remove(oldestKey);
            }
        }
    }

    private void record(String metricName, String result) {
        if (registry == null) return;
        Counter.builder("mangashelf.hotspot.cache.requests")
                .tag("name", metricName)
                .tag("result", result)
                .register(registry)
                .increment();
    }
}
