package com.mangashelf.external.manga;

import com.mangashelf.config.SharedCacheHelper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分区结果缓存（M34 / redis-shared-cache-and-storage）：各源 sections 首次请求可能较慢，
 * 统一缓存 10 分钟，首页切换与聚合复用。Redis 可用时跨实例共享；不可用时降级为实例内缓存。
 */
@Service
public class MangaSectionsService {

    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final Duration REDIS_TTL = Duration.ofMinutes(10);

    private record Entry(List<MangaSource.Section> sections, long expiresAt) {}

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final SharedCacheHelper shared;

    public MangaSectionsService(ObjectProvider<SharedCacheHelper> shared) {
        this.shared = shared.getIfAvailable();
    }

    /** 测试与无 Redis 部署用：纯实例内缓存。 */
    public MangaSectionsService() {
        this.shared = null;
    }

    public List<MangaSource.Section> get(MangaSource source, int limit) throws Exception {
        int size = Math.max(1, Math.min(limit, 30));
        String key = "ms:v1:sections:" + source.id() + ":" + size;
        // 一级：实例内缓存；二级：Redis 共享；未命中回源后两级都写。
        Entry hit = cache.get(key);
        if (hit != null && hit.expiresAt() > System.currentTimeMillis()) {
            return hit.sections();
        }
        if (shared != null) {
            var hit2 = shared.get(key);
            if (hit2.isPresent()) {
                var stored = shared.decodePublic(hit2.get(), StoredSections.class);
                if (stored != null && stored.sections() != null) {
                    cache.put(key, new Entry(stored.sections(), System.currentTimeMillis() + TTL_MS));
                    return stored.sections();
                }
            }
        }
        List<MangaSource.Section> sections = source.sections(size);
        cache.put(key, new Entry(sections, System.currentTimeMillis() + TTL_MS));
        if (shared != null) {
            shared.put(key, shared.encodePublic(new StoredSections(sections)), REDIS_TTL);
        }
        return sections;
    }

    public void invalidate(String sourceId) {
        cache.keySet().removeIf(k -> k.startsWith(sourceId + ":"));
    }

    /** JSON 往返载体：Redis 值经 Jackson 序列化（record 内含 List&lt;Section&gt;）。 */
    record StoredSections(List<MangaSource.Section> sections) {}
}
