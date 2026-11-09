package com.mangashelf.external.manga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨源匹配（M18）：把元数据源（如哔哩漫画）的作品，用标题在可读源（MangaDex）里找同作。
 * 实测 MangaDex 的 title 检索支持中文（航海王 → One Piece），因此无需额外词典。
 * 结果按标题缓存，避免每次榜单都打上游。
 */
@Component
public class MangaMatcher {

    private static final Logger log = LoggerFactory.getLogger(MangaMatcher.class);
    private static final double THRESHOLD = 0.72;
    private static final long TTL_MS = 60 * 60 * 1000;

    private final MangaDexClient mangadex;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(String mangaId, long expiresAt) {}

    public MangaMatcher(MangaDexClient mangadex) {
        this.mangadex = mangadex;
    }

    /** 返回可读源中的作品 id（找不到则为 empty）。 */
    public Optional<String> findReadable(String title) {
        if (title == null || title.isBlank()) return Optional.empty();
        String key = normalize(title);
        CacheEntry hit = cache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAt() > now) {
            return Optional.ofNullable(hit.mangaId());
        }
        try {
            var candidates = mangadex.search(title, null, 5);
            String result = null;
            // 1) 任一标题（含别名）归一化后完全相等
            outer:
            for (var c : candidates) {
                for (String t2 : c.allTitles()) {
                    if (!t2.isBlank() && normalize(t2).equals(key)) { result = c.id(); break outer; }
                }
            }
            // 2) 相似度阈值
            if (result == null) {
                double bestScore = 0;
                for (var c : candidates) {
                    for (String t2 : c.allTitles()) {
                        double score = similarity(key, normalize(t2));
                        if (score > bestScore) { bestScore = score; result = c.id(); }
                    }
                }
                if (bestScore < THRESHOLD) result = null;
            }
            // 3) 回退：MangaDex 的 title 检索本身跨语言（中文名可命中），
            //    无字面重合但检索有结果时取首位，界面标注为跨源匹配
            if (result == null && !candidates.isEmpty() && key.length() >= 2) {
                result = candidates.get(0).id();
            }
            cache.put(key, new CacheEntry(result, now + TTL_MS));
            if (result != null) log.debug("cross-source match '{}' → {}", title, result);
            return Optional.ofNullable(result);
        } catch (Exception e) {
            log.warn("cross-source match failed for '{}': {}", title, e.getMessage());
            // 不缓存失败，下次再试
            return Optional.empty();
        }
    }

    /** 标题归一化：小写、去括号注释/标点/空白、去季期后缀。 */
    static String normalize(String s) {
        if (s == null) return "";
        String v = s.toLowerCase(java.util.Locale.ROOT);
        v = v.replaceAll("（[^）]*）|\\([^)]*\\)|\\[[^]]*]|【[^】]*】", "");
        v = v.replaceAll("[\\s\\p{P}]", "");
        v = v.replaceAll("第[一二三四五六七八九十百0-9]+[季期部篇话集卷]", "");
        v = v.replaceAll("(season|vol|volume)[0-9]+", "");
        return v;
    }

    /** 字符二元组 Jaccard（与动画侧同算法，对中文短标题稳定）。 */
    static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        var sa = bigrams(a);
        var sb = bigrams(b);
        var inter = new java.util.HashSet<>(sa);
        inter.retainAll(sb);
        var union = new java.util.HashSet<>(sa);
        union.addAll(sb);
        return inter.size() / (double) union.size();
    }

    private static java.util.Set<String> bigrams(String s) {
        var out = new java.util.HashSet<String>();
        for (int i = 0; i + 1 < s.length(); i++) out.add(s.substring(i, i + 2));
        return out;
    }
}
