package com.mangashelf.external.manga;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分区结果缓存（M34）：各源 sections 首次请求可能较慢（哔哩跨源桥接、拷贝
 * API 抖动、腾讯多页抓取），这里统一缓存 10 分钟，首页切换与聚合复用。
 */
@Service
public class MangaSectionsService {

    private static final long TTL_MS = 10 * 60 * 1000L;

    private record Entry(List<MangaSource.Section> sections, long expiresAt) {}

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    public List<MangaSource.Section> get(MangaSource source, int limit) throws Exception {
        int size = Math.max(1, Math.min(limit, 30));
        String key = source.id() + ":" + size;
        Entry hit = cache.get(key);
        if (hit != null && hit.expiresAt() > System.currentTimeMillis()) {
            return hit.sections();
        }
        List<MangaSource.Section> sections = source.sections(size);
        cache.put(key, new Entry(sections, System.currentTimeMillis() + TTL_MS));
        return sections;
    }

    public void invalidate(String sourceId) {
        cache.keySet().removeIf(k -> k.startsWith(sourceId + ":"));
    }
}
