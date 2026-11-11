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
 * 多源聚合推荐（M25/M26）：只聚合中文源，RRF 融合名次 + 作品聚簇（同作跨源
 * 合并为一个条目，标 sourceCount/sources）+ 中文标题过滤。
 * 各源并行抓取（虚拟线程），单源超时/失败隔离；结果整体缓存 10 分钟。
 */
@Service
public class MangaDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(MangaDiscoveryService.class);
    private static final int RRF_K = 60;
    /** 单源超时：哔哩首次跨源桥接约 10s、拷贝上游延迟抖动大（实测 1~12s），取 20s。 */
    private static final long TIMEOUT_SECONDS = 20;
    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final Pattern HAN = Pattern.compile("[\\u4e00-\\u9fff]");
    /** 近似标题合并阈值：只合并同一标题的写法差异，版本后缀（全彩版等）不误合并。 */
    private static final double MERGE_THRESHOLD = 0.9;

    /** 聚合条目（M26）：摊平的 RankItem 字段 + 同作合并信息（sourceCount>1 表示跨源同作）。 */
    public record DiscoveryItem(String source, String id, String title, List<String> authors,
                                String coverUrl, boolean finished, String latestLabel,
                                boolean readable, String externalUrl,
                                String readSource, String readId,
                                int sourceCount, List<String> sources) {

        static DiscoveryItem of(MangaSource.RankItem item, int sourceCount, List<String> sources) {
            return new DiscoveryItem(item.source(), item.id(), item.title(), item.authors(),
                    item.coverUrl(), item.finished(), item.latestLabel(), item.readable(),
                    item.externalUrl(), item.readSource(), item.readId(), sourceCount, sources);
        }
    }

    /** 发现流行：sourceId/sourceName 为空表示综合榜。 */
    public record Row(String id, String name, String sourceId, String sourceName,
                      List<DiscoveryItem> items) {}

    public record Result(List<Row> rows, List<String> errors) {}

    private record CacheEntry(Result result, long expiresAt) {}

    private record Scored(String key, MangaSource.RankItem item, double score) {}

    private final MangaSourceRegistry registry;
    private final MangaSectionsService sectionsService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public MangaDiscoveryService(MangaSourceRegistry registry, MangaSectionsService sectionsService) {
        this.registry = registry;
        this.sectionsService = sectionsService;
    }

    public Result discover(int limit) throws Exception {
        int size = Math.max(1, Math.min(limit, 30));
        String key = "v2:" + size;
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
            futures.add(executor.submit(() -> new Fetched(s, sectionsService.get(s, size))));
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
        Map<String, String> sourceNames = registry.all().stream()
                .collect(java.util.stream.Collectors.toMap(MangaSource::id, MangaSource::name));
        List<List<MangaSource.RankItem>> rankedLists = new ArrayList<>();
        for (Fetched f : fetched) {
            for (MangaSource.Section section : f.sections()) {
                if (!section.items().isEmpty()) {
                    rankedLists.add(sanitize(section.items(), zhSourceIds));
                }
            }
        }

        List<Row> rows = new ArrayList<>();
        rows.add(new Row("all", "综合热榜", null, null, fuse(rankedLists, size, sourceNames)));
        for (Fetched f : fetched) {
            for (MangaSource.Section section : f.sections()) {
                List<DiscoveryItem> items = sanitize(section.items(), zhSourceIds).stream()
                        .filter(it -> isChineseTitle(it.title()))
                        .map(it -> DiscoveryItem.of(it, 1, List.of()))
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
     * RRF 融合 + 作品聚簇（M26）：每个榜单按名次计 1/(k+rank)；条目按得分降序
     * 依次归入聚簇——归一化标题精确相等或相似度 ≥0.9 视为同作。聚簇得分累加，
     * 展示条目优先中文源站内可读，sources 为成员源名（按出现顺序去重）。
     */
    static List<DiscoveryItem> fuse(List<List<MangaSource.RankItem>> rankedLists, int limit,
                                    Map<String, String> sourceNames) {
        List<Cluster> clusters = new ArrayList<>();
        // 先收集并按 RRF 得分降序，让高分（更热）版本成为聚簇代表
        List<Scored> scored = new ArrayList<>();
        for (List<MangaSource.RankItem> list : rankedLists) {
            for (int i = 0; i < list.size(); i++) {
                MangaSource.RankItem item = list.get(i);
                if (!isChineseTitle(item.title())) continue;
                String key = normalizeTitle(item.title());
                if (key.isEmpty()) continue;
                scored.add(new Scored(key, item, 1.0 / (RRF_K + i + 1)));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        for (Scored s : scored) {
            Cluster target = null;
            for (Cluster c : clusters) {
                if (c.key.equals(s.key())
                        || MangaMatcher.similarity(c.key, s.key()) >= MERGE_THRESHOLD) {
                    target = c;
                    break;
                }
            }
            if (target == null) {
                clusters.add(new Cluster(s.key(), s.item(), s.score(),
                        new LinkedHashMap<>(Map.of(s.item().source(), s.item().source()))));
            } else {
                target.score += s.score();
                if (!target.item.readable() && s.item().readable()) {
                    target.item = s.item();
                }
                target.sources.putIfAbsent(s.item().source(), s.item().source());
            }
        }
        return clusters.stream()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(Math.max(1, limit))
                .map(c -> DiscoveryItem.of(c.item, c.sources.size(),
                        c.sources.keySet().stream().map(id -> sourceNames.getOrDefault(id, id)).toList()))
                .toList();
    }

    /** 作品聚簇：代表 key + 展示条目 + 累计得分 + 成员源。 */
    private static final class Cluster {
        final String key;
        MangaSource.RankItem item;
        double score;
        final Map<String, String> sources;

        Cluster(String key, MangaSource.RankItem item, double score, Map<String, String> sources) {
            this.key = key;
            this.item = item;
            this.score = score;
            this.sources = sources;
        }
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
