package com.mangashelf.external.manga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 多源聚合推荐（M25）：只聚合中文源，RRF 融合名次 + 标题归一化去重 + 中文标题过滤。
 * 各源并行抓取（虚拟线程），单源超时/失败隔离；结果整体缓存 10 分钟。
 */
@Service
public class MangaDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(MangaDiscoveryService.class);
    private static final int RRF_K = 60;
    /** 哔哩榜单首次做跨源桥接约 10s（之后走匹配缓存），故单源超时取 15s。 */
    private static final long TIMEOUT_SECONDS = 15;
    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final Pattern HAN = Pattern.compile("[\\u4e00-\\u9fff]");

    /** 发现流行：sourceId/sourceName 为空表示综合榜。 */
    public record Row(String id, String name, String sourceId, String sourceName,
                      List<MangaSource.RankItem> items) {}

    public record Result(List<Row> rows, List<String> errors) {}

    private record CacheEntry(Result result, long expiresAt) {}

    private record Scored(MangaSource.RankItem item, double score) {}

    private final MangaSourceRegistry registry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public MangaDiscoveryService(MangaSourceRegistry registry) {
        this.registry = registry;
    }

    public Result discover(int limit) throws Exception {
        int size = Math.max(1, Math.min(limit, 30));
        String key = "v1:" + size;
        CacheEntry hit = cache.get(key);
        if (hit != null && hit.expiresAt() > System.currentTimeMillis()) {
            return hit.result();
        }

        List<MangaSource> sources = registry.all().stream()
                .filter(s -> s.capabilities().contains(MangaSource.Capability.RANK))
                .filter(s -> "zh".equals(s.language()))
                .toList();

        record Fetched(MangaSource source, List<MangaSource.Section> sections) {}
        List<Future<Fetched>> futures = new ArrayList<>();
        for (MangaSource s : sources) {
            futures.add(executor.submit(() -> new Fetched(s, s.sections(size))));
        }

        List<Fetched> fetched = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            MangaSource s = sources.get(i);
            try {
                fetched.add(futures.get(i).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (java.util.concurrent.TimeoutException e) {
                futures.get(i).cancel(true);
                errors.add(s.name() + " 请求超时");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                errors.add(s.name() + " 请求失败：" + cause.getMessage());
                log.warn("discovery source {} failed: {}", s.id(), cause.getMessage());
            }
        }

        java.util.Set<String> zhSourceIds = fetched.stream()
                .map(f -> f.source().id()).collect(java.util.stream.Collectors.toSet());
        List<List<MangaSource.RankItem>> rankedLists = new ArrayList<>();
        for (Fetched f : fetched) {
            for (MangaSource.Section section : f.sections()) {
                if (!section.items().isEmpty()) {
                    rankedLists.add(sanitize(section.items(), zhSourceIds));
                }
            }
        }

        List<Row> rows = new ArrayList<>();
        rows.add(new Row("all", "综合热榜", null, null, fuse(rankedLists, size)));
        for (Fetched f : fetched) {
            for (MangaSource.Section section : f.sections()) {
                List<MangaSource.RankItem> items = sanitize(section.items(), zhSourceIds).stream()
                        .filter(it -> isChineseTitle(it.title()))
                        .toList();
                if (items.isEmpty()) continue;
                rows.add(new Row(f.source().id() + ":" + section.id(), section.name(),
                        f.source().id(), f.source().name(), items));
            }
        }

        Result result = new Result(rows, errors);
        cache.put(key, new CacheEntry(result, System.currentTimeMillis() + TTL_MS));
        return result;
    }

    /**
     * RRF 融合：每个榜单按名次计 1/(k+rank)，同一作品跨榜/跨源累加；
     * 归一化标题去重，同分优先可站内阅读的版本。
     */
    static List<MangaSource.RankItem> fuse(List<List<MangaSource.RankItem>> rankedLists, int limit) {
        Map<String, Scored> merged = new LinkedHashMap<>();
        for (List<MangaSource.RankItem> list : rankedLists) {
            for (int i = 0; i < list.size(); i++) {
                MangaSource.RankItem item = list.get(i);
                if (!isChineseTitle(item.title())) continue;
                String key = normalizeTitle(item.title());
                if (key.isEmpty()) continue;
                double score = 1.0 / (RRF_K + i + 1);
                Scored current = merged.get(key);
                if (current == null) {
                    merged.put(key, new Scored(item, score));
                } else {
                    MangaSource.RankItem keep = current.item();
                    if (!keep.readable() && item.readable()) keep = item;
                    merged.put(key, new Scored(keep, current.score() + score));
                }
            }
        }
        return merged.values().stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(Math.max(1, limit))
                .map(Scored::item)
                .toList();
    }

    /**
     * 只保留中文源站内阅读入口：哔哩榜单跨源桥接到 MangaDex 的条目降级为站外
     * （打开原站），避免从中文首页进入非中文源阅读。
     */
    static List<MangaSource.RankItem> sanitize(List<MangaSource.RankItem> items,
                                               java.util.Set<String> zhSourceIds) {
        return items.stream().map(it -> {
            boolean foreignRead = it.readId() != null && it.readSource() != null
                    && !zhSourceIds.contains(it.readSource());
            if (!foreignRead) return it;
            return new MangaSource.RankItem(it.source(), it.id(), it.title(), it.authors(),
                    it.coverUrl(), it.finished(), it.latestLabel(), false, it.externalUrl(),
                    null, null);
        }).toList();
    }

    /** 标题归一化：NFKC + 小写 + 去空白/标点/符号（保留括号内文字，区分全彩版等）。 */
    static String normalizeTitle(String title) {
        if (title == null) return "";
        String v = Normalizer.normalize(title, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return v.replaceAll("[\\s\\p{P}\\p{S}]", "");
    }

    /** 中文内容过滤：标题必须含汉字。 */
    static boolean isChineseTitle(String title) {
        return title != null && HAN.matcher(title).find();
    }
}
