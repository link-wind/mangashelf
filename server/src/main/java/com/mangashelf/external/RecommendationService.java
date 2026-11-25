package com.mangashelf.external;

import com.mangashelf.external.manga.MangaCatalogRepository;
import com.mangashelf.external.manga.MangaDiscoveryService;
import com.mangashelf.external.manga.MangaSource;
import com.mangashelf.external.manga.MangaSourceRegistry;
import com.mangashelf.identity.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 动画与漫画轻量推荐服务（revamp-discovery-search-index-and-nav）：
 * 不引入外部 ML 框架，基于现有收藏、进度与元数据表（显式 SQL + 确定性打分 + 多样性打散）
 * 提供追更提醒、可解释个性化推荐与本季精选；访客仅返回公开精选，不读取私有数据。
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
    private static final double BAYESIAN_PRIOR_MEAN = 7.2;
    private static final double BAYESIAN_PRIOR_WEIGHT = 5.0;
    private static final double BAYESIAN_SAMPLE_WEIGHT = 10.0;
    private static final Set<String> NOISE_TAGS = Set.of(
            "TV", "日本", "中国", "OVA", "WEB", "动画", "番剧", "剧场版", "新番", "国产动画");

    private final JdbcTemplate jdbc;
    private final BangumiService bangumi;
    private final MangaDiscoveryService mangaDiscovery;
    private final MangaCatalogRepository mangaCatalog;
    private final MangaSourceRegistry mangaRegistry;

    public RecommendationService(JdbcTemplate jdbc,
                                 BangumiService bangumi,
                                 MangaDiscoveryService mangaDiscovery,
                                 MangaCatalogRepository mangaCatalog,
                                 MangaSourceRegistry mangaRegistry) {
        this.jdbc = jdbc;
        this.bangumi = bangumi;
        this.mangaDiscovery = mangaDiscovery;
        this.mangaCatalog = mangaCatalog;
        this.mangaRegistry = mangaRegistry;
    }

    // ── 动画推荐数据结构 ──

    /** 追更提醒：已看/收藏连载番资源站更新了新集。 */
    public record AnimeCatchUpItem(UUID catalogItemId, UUID workId, String title, String coverUrl,
                                   String remarks, int watchedOrdinal, int latestOrdinal,
                                   int newEpisodes, UUID resumeEpisodeId, Instant updatedAt) {}

    /** 动画推荐卡片（带可解释推荐理由与匹配标签）。 */
    public record AnimeRecItem(UUID catalogItemId, UUID workId, Long bangumiSubjectId,
                               String title, String coverUrl, String remarks,
                               String categoryName, int episodeCount, Double rating,
                               List<String> matchedTags, String reason, double score) {}

    /** 本季精选条目（供首页 Hero 与必看榜使用）。 */
    public record SeasonalPick(long subjectId, String name, String nameCn, String coverUrl,
                               Double rating, Integer airWeekday, boolean covered,
                               String catalogItemId, String workId, String summary,
                               String remarks, Integer episodeCount, String categoryName,
                               double rankScore) {}

    public record AnimeRecommendationResult(boolean personalized,
                                            List<String> topPreferences,
                                            List<AnimeCatchUpItem> catchUp,
                                            List<AnimeRecItem> forYou,
                                            List<SeasonalPick> seasonalPicks,
                                            int totalCandidates) {}

    // ── 漫画推荐数据结构 ──

    public record MangaRecItem(String source, String sourceName, String id, String title,
                               String coverUrl, String remarks, boolean readable,
                               int sourceCount, List<String> sources,
                               List<String> matchedTags, String reason, double score) {}

    public record MangaRecommendationResult(boolean personalized,
                                            List<String> topPreferences,
                                            List<MangaRecItem> forYou,
                                            int totalCandidates) {}

    // ── 动画推荐主流程 ──

    public AnimeRecommendationResult recommendAnime(CurrentUser user, int limit, int offset) {
        int size = Math.min(Math.max(limit, 1), 24);
        int safeOffset = Math.max(offset, 0);

        boolean signedIn = user != null && !user.isGuest();
        List<SeasonalPick> seasonalPicks = buildSeasonalPicks();
        List<AnimeCatchUpItem> catchUp = signedIn ? findAnimeCatchUp(user.id()) : List.of();

        Map<String, Double> tagWeights = new HashMap<>();
        Map<Integer, Double> categoryWeights = new HashMap<>();
        Set<UUID> excludedIds = new HashSet<>();
        if (signedIn) {
            loadAnimeUserProfile(user.id(), tagWeights, categoryWeights, excludedIds);
        }
        boolean personalized = !tagWeights.isEmpty() || !categoryWeights.isEmpty();
        List<String> topPrefs = tagWeights.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();

        List<AnimeCandidate> candidates = loadAnimeCandidates(300);
        Instant now = Instant.now();
        List<AnimeRecItem> scored = new ArrayList<>();
        for (AnimeCandidate c : candidates) {
            if (excludedIds.contains(c.id())) continue;
            scored.add(scoreAnimeCandidate(c, tagWeights, categoryWeights, now));
        }
        scored.sort(Comparator.comparingDouble(AnimeRecItem::score).reversed());

        List<AnimeRecItem> diversified = diversifyAnimeByPrimaryTag(scored, Math.min(scored.size(), 60));
        List<AnimeRecItem> page = sliceWithRotation(diversified, size, safeOffset);

        return new AnimeRecommendationResult(personalized, topPrefs, catchUp, page,
                seasonalPicks, diversified.size());
    }

    /** 播放页/详情页相似番剧推荐：按共享题材标签 + 同分区 + 贝叶斯评分召回（排除自身）。 */
    public List<AnimeRecItem> similarAnime(UUID catalogItemId, int limit) {
        if (catalogItemId == null) return List.of();
        int size = Math.min(Math.max(limit, 1), 12);
        List<AnimeCandidate> anchorRows = jdbc.query("""
                SELECT e.id, e.work_id, e.bangumi_subject_id, e.name, e.cover_url, e.remarks,
                       e.category_id, e.category_name, e.episode_count,
                       b.rating, b.tags, COALESCE(b.in_calendar, false) AS in_calendar,
                       COALESCE(e.resource_updated_at, e.synced_at) AS updated_at
                  FROM external_catalog_items e
                  LEFT JOIN bangumi_subjects b ON b.subject_id = e.bangumi_subject_id
                 WHERE e.id = ?
                """, (rs, i) -> {
            UUID id = rs.getObject("id", UUID.class);
            Long bgmId = (Long) rs.getObject("bangumi_subject_id");
            var ratingDec = rs.getBigDecimal("rating");
            return new AnimeCandidate(
                    id, rs.getObject("work_id", UUID.class), bgmId,
                    rs.getString("name"), resolveCatalogCover(id, bgmId, rs.getString("cover_url")),
                    rs.getString("remarks"), (Integer) rs.getObject("category_id"),
                    rs.getString("category_name"), rs.getInt("episode_count"),
                    ratingDec == null ? null : ratingDec.doubleValue(),
                    extractTags(rs.getArray("tags")), rs.getBoolean("in_calendar"),
                    toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
        }, catalogItemId);
        if (anchorRows.isEmpty()) return List.of();

        AnimeCandidate anchor = anchorRows.getFirst();
        Map<String, Double> tagWeights = new HashMap<>();
        if (anchor.tags() != null) {
            for (String t : anchor.tags()) tagWeights.put(t, 1.0);
        }
        Map<Integer, Double> categoryWeights = anchor.categoryId() != null
                ? Map.of(anchor.categoryId(), 1.0) : Map.of();

        List<AnimeCandidate> candidates = loadAnimeCandidates(180);
        Instant now = Instant.now();
        List<AnimeRecItem> scored = new ArrayList<>();
        for (AnimeCandidate c : candidates) {
            if (catalogItemId.equals(c.id())) continue;
            scored.add(scoreSimilarAnime(anchor, c, tagWeights, categoryWeights, now));
        }
        scored.sort(Comparator.comparingDouble(AnimeRecItem::score).reversed());
        return scored.stream().limit(size).toList();
    }

    static AnimeRecItem scoreSimilarAnime(AnimeCandidate anchor, AnimeCandidate cand,
                                          Map<String, Double> anchorTags,
                                          Map<Integer, Double> anchorCat,
                                          Instant now) {
        AnimeRecItem base = scoreAnimeCandidate(cand, anchorTags, anchorCat, now);
        String reason = !base.matchedTags().isEmpty()
                ? "同题材 · " + String.join("·", base.matchedTags().subList(0, Math.min(2, base.matchedTags().size())))
                : (anchor.categoryId() != null && anchor.categoryId().equals(cand.categoryId())
                        && cand.categoryName() != null && !cand.categoryName().isBlank()
                        ? "同属 " + cand.categoryName()
                        : base.reason());
        return new AnimeRecItem(base.catalogItemId(), base.workId(), base.bangumiSubjectId(),
                base.title(), base.coverUrl(), base.remarks(), base.categoryName(),
                base.episodeCount(), base.rating(), base.matchedTags(), reason, base.score());
    }

    /** 本季精选：优先可播 + 贝叶斯评分 + 近期播出日（今/昨）加成。 */
    private List<SeasonalPick> buildSeasonalPicks() {
        List<BangumiService.CalendarEntry> calendar = bangumi.calendar();
        int todayDow = java.time.LocalDate.now().getDayOfWeek().getValue();
        int yesterdayDow = todayDow == 1 ? 7 : todayDow - 1;
        return calendar.stream()
                .map(c -> {
                    double bayes = bayesianRating(c.rating());
                    double coveredBonus = c.covered() ? 0.40 : 0.0;
                    double airBonus = (c.airWeekday() != null
                            && (c.airWeekday() == todayDow || c.airWeekday() == yesterdayDow)) ? 0.10 : 0.0;
                    double epBonus = c.episodeCount() != null && c.episodeCount() > 0 ? 0.05 : 0.0;
                    double rank = coveredBonus + 0.45 * bayes + airBonus + epBonus;
                    return new SeasonalPick(c.subjectId(), c.name(), c.nameCn(), c.coverUrl(),
                            c.rating(), c.airWeekday(), c.covered(), c.catalogItemId(), c.workId(),
                            c.summary(), c.remarks(), c.episodeCount(), c.categoryName(), rank);
                })
                .sorted(Comparator.comparingDouble(SeasonalPick::rankScore).reversed())
                .limit(12)
                .toList();
    }

    /** 追更提醒：检测用户观看过的目录/作品是否更新了新集。 */
    private List<AnimeCatchUpItem> findAnimeCatchUp(UUID userId) {
        Map<UUID, AnimeCatchUpItem> merged = new LinkedHashMap<>();

        // 1. 浏览态观看进度 vs 目录最新集数
        jdbc.query("""
                SELECT DISTINCT ON (p.catalog_item_id)
                       c.id AS catalog_item_id, c.work_id, c.name, c.remarks,
                       c.episode_count, c.bangumi_subject_id, c.cover_url,
                       p.ordinal AS watched_ordinal, p.episode_id, p.updated_at
                  FROM catalog_watch_progress p
                  JOIN external_catalog_items c ON c.id = p.catalog_item_id
                 WHERE p.user_id = ?
                 ORDER BY p.catalog_item_id, p.ordinal DESC, p.updated_at DESC
                """, (rs, i) -> {
            UUID cid = rs.getObject("catalog_item_id", UUID.class);
            int watched = rs.getInt("watched_ordinal");
            int latest = rs.getInt("episode_count");
            if (latest > watched && latest > 0) {
                Long bgmId = (Long) rs.getObject("bangumi_subject_id");
                String cover = resolveCatalogCover(cid, bgmId, rs.getString("cover_url"));
                merged.put(cid, new AnimeCatchUpItem(cid, rs.getObject("work_id", UUID.class),
                        rs.getString("name"), cover, rs.getString("remarks"),
                        watched, latest, latest - watched,
                        rs.getObject("episode_id", UUID.class),
                        toInstant(rs.getObject("updated_at", OffsetDateTime.class))));
            }
            return null;
        }, userId);

        // 2. 物化作品态观看进度 vs 目录最新集数
        jdbc.query("""
                SELECT DISTINCT ON (p.work_id)
                       c.id AS catalog_item_id, p.work_id, COALESCE(w.title, c.name) AS title,
                       c.remarks, c.episode_count, c.bangumi_subject_id, c.cover_url,
                       p.ordinal AS watched_ordinal, p.episode_id, p.updated_at
                  FROM external_watch_progress p
                  JOIN works w ON w.id = p.work_id
                  JOIN external_catalog_items c ON c.work_id = p.work_id
                 WHERE p.user_id = ?
                 ORDER BY p.work_id, p.ordinal DESC, p.updated_at DESC
                """, (rs, i) -> {
            UUID cid = rs.getObject("catalog_item_id", UUID.class);
            int watched = rs.getInt("watched_ordinal");
            int latest = rs.getInt("episode_count");
            if (latest > watched && latest > 0) {
                AnimeCatchUpItem prev = merged.get(cid);
                if (prev == null || watched > prev.watchedOrdinal()) {
                    Long bgmId = (Long) rs.getObject("bangumi_subject_id");
                    String cover = resolveCatalogCover(cid, bgmId, rs.getString("cover_url"));
                    merged.put(cid, new AnimeCatchUpItem(cid, rs.getObject("work_id", UUID.class),
                            rs.getString("title"), cover, rs.getString("remarks"),
                            watched, latest, latest - watched,
                            rs.getObject("episode_id", UUID.class),
                            toInstant(rs.getObject("updated_at", OffsetDateTime.class))));
                }
            }
            return null;
        }, userId);

        return merged.values().stream()
                .sorted(Comparator.comparing(AnimeCatchUpItem::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(12)
                .toList();
    }

    private void loadAnimeUserProfile(UUID userId,
                                      Map<String, Double> tagWeights,
                                      Map<Integer, Double> categoryWeights,
                                      Set<UUID> excludedIds) {
        Instant recentCutoff = Instant.now().minus(Duration.ofDays(14));

        // 收藏的番剧：高权重 + 排除出"新番推荐"
        jdbc.query("""
                SELECT c.id, c.category_id, b.tags, f.created_at
                  FROM favorites f
                  JOIN external_catalog_items c ON c.id = f.catalog_item_id
                  LEFT JOIN bangumi_subjects b ON b.subject_id = c.bangumi_subject_id
                 WHERE f.user_id = ? AND f.catalog_item_id IS NOT NULL
                   AND f.created_at >= now() - interval '180 days'
                """, (rs, i) -> {
            UUID id = rs.getObject("id", UUID.class);
            excludedIds.add(id);
            double w = 3.0;
            Instant ts = toInstant(rs.getObject("created_at", OffsetDateTime.class));
            if (ts != null && ts.isAfter(recentCutoff)) w *= 1.3;
            addWeights(tagWeights, categoryWeights, extractTags(rs.getArray("tags")),
                    (Integer) rs.getObject("category_id"), w);
            return null;
        }, userId);

        // 观看过的目录条目
        jdbc.query("""
                SELECT c.id, c.category_id, c.episode_count, b.tags,
                       max(p.ordinal) AS max_ord, max(p.updated_at) AS last_seen
                  FROM catalog_watch_progress p
                  JOIN external_catalog_items c ON c.id = p.catalog_item_id
                  LEFT JOIN bangumi_subjects b ON b.subject_id = c.bangumi_subject_id
                 WHERE p.user_id = ?
                   AND p.updated_at >= now() - interval '90 days'
                 GROUP BY c.id, c.category_id, c.episode_count, b.tags
                """, (rs, i) -> {
            UUID id = rs.getObject("id", UUID.class);
            excludedIds.add(id);
            int maxOrd = rs.getInt("max_ord");
            double w = maxOrd > 1 ? 2.4 : 1.6;
            Instant ts = toInstant(rs.getObject("last_seen", OffsetDateTime.class));
            if (ts != null && ts.isAfter(recentCutoff)) w *= 1.3;
            addWeights(tagWeights, categoryWeights, extractTags(rs.getArray("tags")),
                    (Integer) rs.getObject("category_id"), w);
            return null;
        }, userId);

        // 观看过的已物化作品
        jdbc.query("""
                SELECT c.id, c.category_id, b.tags, max(p.ordinal) AS max_ord, max(p.updated_at) AS last_seen
                  FROM external_watch_progress p
                  JOIN external_catalog_items c ON c.work_id = p.work_id
                  LEFT JOIN bangumi_subjects b ON b.subject_id = c.bangumi_subject_id
                 WHERE p.user_id = ?
                   AND p.updated_at >= now() - interval '90 days'
                 GROUP BY c.id, c.category_id, b.tags
                """, (rs, i) -> {
            UUID id = rs.getObject("id", UUID.class);
            excludedIds.add(id);
            int maxOrd = rs.getInt("max_ord");
            double w = maxOrd > 1 ? 2.4 : 1.6;
            Instant ts = toInstant(rs.getObject("last_seen", OffsetDateTime.class));
            if (ts != null && ts.isAfter(recentCutoff)) w *= 1.3;
            addWeights(tagWeights, categoryWeights, extractTags(rs.getArray("tags")),
                    (Integer) rs.getObject("category_id"), w);
            return null;
        }, userId);

        normalizeWeights(tagWeights);
        normalizeWeights(categoryWeights);
    }

    record AnimeCandidate(UUID id, UUID workId, Long bangumiSubjectId, String name, String coverUrl,
                          String remarks, Integer categoryId, String categoryName, int episodeCount,
                          Double rating, List<String> tags, boolean inCalendar, Instant updatedAt) {}

    private List<AnimeCandidate> loadAnimeCandidates(int limit) {
        return jdbc.query("""
                SELECT e.id, e.work_id, e.bangumi_subject_id, e.name, e.cover_url, e.remarks,
                       e.category_id, e.category_name, e.episode_count,
                       b.rating, b.tags, COALESCE(b.in_calendar, false) AS in_calendar,
                       COALESCE(e.resource_updated_at, e.synced_at) AS updated_at
                  FROM external_catalog_items e
                  LEFT JOIN bangumi_subjects b ON b.subject_id = e.bangumi_subject_id
                 ORDER BY (e.bangumi_subject_id IS NOT NULL) DESC,
                          COALESCE(b.in_calendar, false) DESC,
                          b.rating DESC NULLS LAST,
                          COALESCE(e.resource_updated_at, e.synced_at) DESC
                 LIMIT ?
                """, (rs, i) -> {
            UUID id = rs.getObject("id", UUID.class);
            Long bgmId = (Long) rs.getObject("bangumi_subject_id");
            var ratingDec = rs.getBigDecimal("rating");
            return new AnimeCandidate(
                    id, rs.getObject("work_id", UUID.class), bgmId,
                    rs.getString("name"), resolveCatalogCover(id, bgmId, rs.getString("cover_url")),
                    rs.getString("remarks"), (Integer) rs.getObject("category_id"),
                    rs.getString("category_name"), rs.getInt("episode_count"),
                    ratingDec == null ? null : ratingDec.doubleValue(),
                    extractTags(rs.getArray("tags")), rs.getBoolean("in_calendar"),
                    toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
        }, limit);
    }

    static AnimeRecItem scoreAnimeCandidate(AnimeCandidate c,
                                            Map<String, Double> tagWeights,
                                            Map<Integer, Double> categoryWeights,
                                            Instant now) {
        double tagScore = 0.0;
        List<String> matched = new ArrayList<>();
        if (!tagWeights.isEmpty() && c.tags() != null) {
            List<Map.Entry<String, Double>> hits = new ArrayList<>();
            for (String t : c.tags()) {
                Double w = tagWeights.get(t);
                if (w != null && w > 0) {
                    hits.add(Map.entry(t, w));
                }
            }
            hits.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            double sum = 0.0;
            for (int i = 0; i < Math.min(3, hits.size()); i++) {
                sum += hits.get(i).getValue();
                matched.add(hits.get(i).getKey());
            }
            tagScore = Math.min(1.0, sum / 1.8);
        }

        double bayes = bayesianRating(c.rating());
        double freshness = freshnessScore(c.updatedAt(), now);
        if (c.inCalendar()) freshness = Math.min(1.0, freshness + 0.25);
        double catScore = c.categoryId() != null ? categoryWeights.getOrDefault(c.categoryId(), 0.0) : 0.0;

        boolean hasProfile = !tagWeights.isEmpty() || !categoryWeights.isEmpty();
        double finalScore = hasProfile
                ? 0.45 * tagScore + 0.30 * bayes + 0.15 * freshness + 0.10 * catScore
                : 0.55 * bayes + 0.35 * freshness + (c.inCalendar() ? 0.10 : 0.0);

        String reason;
        if (!matched.isEmpty()) {
            reason = "常看 " + String.join("·", matched.subList(0, Math.min(2, matched.size())));
        } else if (catScore >= 0.6 && c.categoryName() != null && !c.categoryName().isBlank()) {
            reason = "常看分区 · " + c.categoryName();
        } else if (c.inCalendar() && c.rating() != null && c.rating() >= 7.5) {
            reason = String.format(Locale.ROOT, "本季高分 %.1f", c.rating());
        } else if (c.inCalendar()) {
            reason = "本季热门可播";
        } else if (c.rating() != null && c.rating() >= 7.5) {
            reason = String.format(Locale.ROOT, "口碑佳作 %.1f", c.rating());
        } else {
            reason = "近期热更";
        }

        return new AnimeRecItem(c.id(), c.workId(), c.bangumiSubjectId(), c.name(),
                c.coverUrl(), c.remarks(), c.categoryName(), c.episodeCount(), c.rating(),
                matched, reason, finalScore);
    }

    /** 同主题材连续不超过 2 部，避免推荐行过于同质化。 */
    static List<AnimeRecItem> diversifyAnimeByPrimaryTag(List<AnimeRecItem> ordered, int limit) {
        List<AnimeRecItem> pool = new ArrayList<>(ordered);
        List<AnimeRecItem> out = new ArrayList<>(Math.min(limit, pool.size()));
        String lastKey = null;
        int streak = 0;
        while (out.size() < limit && !pool.isEmpty()) {
            int pickIdx = 0;
            if (lastKey != null && streak >= 2) {
                for (int i = 0; i < pool.size(); i++) {
                    String k = animeGroupKey(pool.get(i));
                    if (!lastKey.equals(k)) {
                        pickIdx = i;
                        break;
                    }
                }
            }
            AnimeRecItem picked = pool.remove(pickIdx);
            out.add(picked);
            String k = animeGroupKey(picked);
            if (k != null && k.equals(lastKey)) {
                streak++;
            } else {
                lastKey = k;
                streak = 1;
            }
        }
        return out;
    }

    private static String animeGroupKey(AnimeRecItem item) {
        if (item.matchedTags() != null && !item.matchedTags().isEmpty()) {
            return item.matchedTags().getFirst();
        }
        return item.categoryName();
    }

    // ── 漫画推荐主流程 ──

    public MangaRecommendationResult recommendManga(CurrentUser user, int limit, int offset) {
        int size = Math.min(Math.max(limit, 1), 24);
        int safeOffset = Math.max(offset, 0);

        Map<String, String> sourceNames = new HashMap<>();
        Set<String> readableSources = new HashSet<>();
        for (MangaSource s : mangaRegistry.all()) {
            sourceNames.put(s.id(), s.name());
            if (s.capabilities().contains(MangaSource.Capability.READ)) {
                readableSources.add(s.id());
            }
        }

        Map<String, Double> tagWeights = new HashMap<>();
        Set<String> excludedKeys = new HashSet<>();
        if (user != null && !user.isGuest()) {
            loadMangaUserProfile(user.id(), tagWeights, excludedKeys);
        }
        boolean personalized = !tagWeights.isEmpty();
        List<String> topPrefs = tagWeights.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();

        // 汇总候选：发现流综合热榜 + 目录库近期条目（按归一化标题去重）
        Map<String, MangaCandidate> pool = new LinkedHashMap<>();
        try {
            MangaDiscoveryService.Result disc = mangaDiscovery.discover(24);
            if (disc != null && disc.rows() != null) {
                for (MangaDiscoveryService.Row row : disc.rows()) {
                    boolean isCombined = "all".equals(row.id());
                    for (int i = 0; i < row.items().size(); i++) {
                        MangaDiscoveryService.DiscoveryItem it = row.items().get(i);
                        String norm = normalizeMangaKey(it.title());
                        if (norm.isEmpty() || excludedKeys.contains(norm)
                                || excludedKeys.contains(it.source() + ":" + it.id())) {
                            continue;
                        }
                        double hotness = (isCombined ? 1.0 : 0.7) * (1.0 - (double) i / Math.max(24, row.items().size() + 2));
                        String effectiveSource = (it.readSource() != null && !it.readSource().isBlank())
                                ? it.readSource() : it.source();
                        String effectiveId = (it.readId() != null && !it.readId().isBlank())
                                ? it.readId() : it.id();
                        boolean canRead = it.readable() || readableSources.contains(effectiveSource);
                        String remarks = it.latestLabel() != null && !it.latestLabel().isBlank()
                                ? it.latestLabel() : (it.finished() ? "已完结" : "");
                        pool.merge(norm, new MangaCandidate(effectiveSource,
                                        sourceNames.getOrDefault(effectiveSource, effectiveSource),
                                        effectiveId, it.title(), it.coverUrl(), remarks, canRead,
                                        Math.max(1, it.sourceCount()),
                                        it.sources() == null ? List.of() : it.sources(),
                                        List.of(), hotness, Instant.now()),
                                MangaCandidate::merge);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("manga recommendation discovery fallback: {}", e.getMessage());
        }

        for (MangaCatalogRepository.MangaCatalogItem c : mangaCatalog.recent(180)) {
            String norm = normalizeMangaKey(c.name());
            if (norm.isEmpty() || excludedKeys.contains(norm)
                    || excludedKeys.contains(c.source() + ":" + c.sourceItemId())) {
                continue;
            }
            boolean canRead = readableSources.contains(c.source());
            String srcName = sourceNames.getOrDefault(c.source(), c.source());
            String remarks = c.remarks() != null && !c.remarks().isBlank()
                    ? c.remarks()
                    : (c.episodeCount() != null ? c.episodeCount() + " 话" : "");
            List<String> cleanTags = c.tags() == null ? List.of()
                    : c.tags().stream().filter(RecommendationService::isValidTag).toList();
            pool.merge(norm, new MangaCandidate(c.source(), srcName, c.sourceItemId(), c.name(),
                            c.coverUrl() == null || c.coverUrl().isBlank() ? null : c.coverUrl(),
                            remarks, canRead, 1, List.of(srcName), cleanTags, 0.35, c.syncedAt()),
                    MangaCandidate::merge);
        }

        Instant now = Instant.now();
        List<MangaRecItem> scored = pool.values().stream()
                .map(c -> scoreMangaCandidate(c, tagWeights, now))
                .sorted(Comparator.comparingDouble(MangaRecItem::score).reversed())
                .toList();

        List<MangaRecItem> page = sliceWithRotation(scored, size, safeOffset);
        return new MangaRecommendationResult(personalized, topPrefs, page, scored.size());
    }

    private void loadMangaUserProfile(UUID userId, Map<String, Double> tagWeights, Set<String> excludedKeys) {
        // 收藏的外部漫画
        jdbc.query("""
                SELECT f.manga_source, f.manga_id,
                       COALESCE(NULLIF(f.manga_title, ''), NULLIF(mc.name, ''), f.manga_id) AS title,
                       mc.tags
                  FROM favorites f
                  LEFT JOIN manga_catalog_items mc
                         ON mc.source = f.manga_source AND mc.source_item_id = f.manga_id
                 WHERE f.user_id = ? AND f.manga_id IS NOT NULL
                """, (rs, i) -> {
            String src = rs.getString("manga_source");
            String mid = rs.getString("manga_id");
            excludedKeys.add(src + ":" + mid);
            String norm = normalizeMangaKey(rs.getString("title"));
            if (!norm.isEmpty()) excludedKeys.add(norm);
            for (String t : extractTags(rs.getArray("tags"))) {
                tagWeights.merge(t, 3.0, Double::sum);
            }
            return null;
        }, userId);

        // 外部漫画阅读进度
        jdbc.query("""
                SELECT p.source, p.manga_id, p.title, mc.tags
                  FROM external_manga_progress p
                  LEFT JOIN manga_catalog_items mc
                         ON mc.source = p.source AND mc.source_item_id = p.manga_id
                 WHERE p.user_id = ?
                   AND p.updated_at >= now() - interval '90 days'
                """, (rs, i) -> {
            String src = rs.getString("source");
            String mid = rs.getString("manga_id");
            excludedKeys.add(src + ":" + mid);
            String norm = normalizeMangaKey(rs.getString("title"));
            if (!norm.isEmpty()) excludedKeys.add(norm);
            for (String t : extractTags(rs.getArray("tags"))) {
                tagWeights.merge(t, 2.0, Double::sum);
            }
            return null;
        }, userId);

        normalizeWeights(tagWeights);
    }

    record MangaCandidate(String source, String sourceName, String id, String title, String coverUrl,
                          String remarks, boolean readable, int sourceCount, List<String> sources,
                          List<String> tags, double hotness, Instant syncedAt) {
        static MangaCandidate merge(MangaCandidate a, MangaCandidate b) {
            MangaCandidate primary = (!a.readable && b.readable) ? b : a;
            String cover = (primary.coverUrl != null && !primary.coverUrl.isBlank())
                    ? primary.coverUrl : (a.coverUrl != null && !a.coverUrl.isBlank() ? a.coverUrl : b.coverUrl);
            String remarks = (primary.remarks != null && !primary.remarks.isBlank())
                    ? primary.remarks : (a.remarks != null && !a.remarks.isBlank() ? a.remarks : b.remarks);
            List<String> mergedTags = new ArrayList<>();
            if (a.tags != null) mergedTags.addAll(a.tags);
            if (b.tags != null) {
                for (String t : b.tags) {
                    if (!mergedTags.contains(t)) mergedTags.add(t);
                }
            }
            List<String> mergedSources = new ArrayList<>();
            if (a.sources != null) mergedSources.addAll(a.sources);
            if (b.sources != null) {
                for (String s : b.sources) {
                    if (!mergedSources.contains(s)) mergedSources.add(s);
                }
            }
            return new MangaCandidate(primary.source, primary.sourceName, primary.id, primary.title,
                    cover, remarks, a.readable || b.readable,
                    Math.max(Math.max(a.sourceCount, b.sourceCount), mergedSources.size()),
                    mergedSources, mergedTags, Math.max(a.hotness, b.hotness),
                    a.syncedAt.isAfter(b.syncedAt) ? a.syncedAt : b.syncedAt);
        }
    }

    static MangaRecItem scoreMangaCandidate(MangaCandidate c, Map<String, Double> tagWeights, Instant now) {
        double tagScore = 0.0;
        List<String> matched = new ArrayList<>();
        if (!tagWeights.isEmpty() && c.tags() != null) {
            List<Map.Entry<String, Double>> hits = new ArrayList<>();
            for (String t : c.tags()) {
                Double w = tagWeights.get(t);
                if (w != null && w > 0) hits.add(Map.entry(t, w));
            }
            hits.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            double sum = 0.0;
            for (int i = 0; i < Math.min(3, hits.size()); i++) {
                sum += hits.get(i).getValue();
                matched.add(hits.get(i).getKey());
            }
            tagScore = Math.min(1.0, sum / 1.6);
        }

        double readableScore = c.readable() ? 1.0 : 0.35;
        double consensusScore = Math.min(1.0, (c.sourceCount() - 1) * 0.45);
        double freshness = freshnessScore(c.syncedAt(), now);

        boolean hasProfile = !tagWeights.isEmpty();
        double finalScore = hasProfile
                ? 0.42 * tagScore + 0.28 * c.hotness() + 0.18 * readableScore + 0.07 * consensusScore + 0.05 * freshness
                : 0.50 * c.hotness() + 0.25 * readableScore + 0.18 * consensusScore + 0.07 * freshness;

        String reason;
        if (!matched.isEmpty()) {
            reason = "常看 " + String.join("·", matched.subList(0, Math.min(2, matched.size())));
        } else if (c.sourceCount() > 1 && c.readable()) {
            reason = c.sourceCount() + " 源热推 · 站内可读";
        } else if (c.readable()) {
            reason = c.sourceName() + " · 站内可读";
        } else {
            reason = c.sourceName() + " 热门";
        }

        return new MangaRecItem(c.source(), c.sourceName(), c.id(), c.title(), c.coverUrl(),
                c.remarks(), c.readable(), c.sourceCount(), c.sources(), matched, reason, finalScore);
    }

    // ── 通用工具函数 ──

    /** 贝叶斯平滑评分（映射到 0..1），压制样本极少的虚高分。 */
    static double bayesianRating(Double rating) {
        if (rating == null || rating <= 0) {
            return (BAYESIAN_PRIOR_MEAN / 10.0) * 0.85;
        }
        double smoothed = (rating * BAYESIAN_SAMPLE_WEIGHT + BAYESIAN_PRIOR_MEAN * BAYESIAN_PRIOR_WEIGHT)
                / (BAYESIAN_SAMPLE_WEIGHT + BAYESIAN_PRIOR_WEIGHT);
        return Math.min(1.0, Math.max(0.0, smoothed / 10.0));
    }

    /** 活跃度衰减（30 天半衰期）。 */
    static double freshnessScore(Instant updatedAt, Instant now) {
        if (updatedAt == null) return 0.3;
        long days = Math.max(0, Duration.between(updatedAt, now).toDays());
        return 1.0 / (1.0 + days / 30.0);
    }

    /** 循环切片（支持前端「换一批」）。 */
    static <T> List<T> sliceWithRotation(List<T> items, int limit, int offset) {
        if (items.isEmpty()) return List.of();
        if (items.size() <= limit) return List.copyOf(items);
        int start = Math.floorMod(offset, items.size());
        List<T> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            out.add(items.get((start + i) % items.size()));
        }
        return out;
    }

    private static void addWeights(Map<String, Double> tagWeights,
                                   Map<Integer, Double> categoryWeights,
                                   List<String> tags, Integer categoryId, double weight) {
        for (String t : tags) {
            tagWeights.merge(t, weight, Double::sum);
        }
        if (categoryId != null) {
            categoryWeights.merge(categoryId, weight, Double::sum);
        }
    }

    private static <K> void normalizeWeights(Map<K, Double> map) {
        double max = map.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        if (max <= 0) return;
        for (var e : map.entrySet()) {
            e.setValue(e.getValue() / max);
        }
    }

    private static List<String> extractTags(java.sql.Array sqlArray) {
        if (sqlArray == null) return List.of();
        try {
            Object raw = sqlArray.getArray();
            if (!(raw instanceof String[] arr)) return List.of();
            List<String> out = new ArrayList<>();
            for (String s : arr) {
                if (isValidTag(s)) out.add(s.trim());
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean isValidTag(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (s.length() < 2 || s.length() > 10) return false;
        if (NOISE_TAGS.contains(s)) return false;
        if (Character.isDigit(s.charAt(0))) return false;
        return !s.startsWith("更新") && !s.startsWith("第");
    }

    private static String resolveCatalogCover(UUID catalogId, Long bgmId, String rawCover) {
        if (bgmId != null) {
            return "/api/v1/bangumi-covers/" + bgmId + "?size=large";
        }
        if (rawCover != null && !rawCover.isBlank()) {
            return rawCover.startsWith("http") ? "/api/v1/catalog-covers/" + catalogId : rawCover;
        }
        return "";
    }

    private static String normalizeMangaKey(String title) {
        if (title == null) return "";
        return java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{P}\\p{S}]", "");
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}
