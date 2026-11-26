package com.mangashelf.external.manga;

import com.mangashelf.external.CatalogRanking;
import com.mangashelf.external.TagNormalizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 漫画目录（manga-catalog-sync）：全量/渐进式同步的条目，按 (source, source_item_id) 唯一。
 * 全源查询（source 为空/all）按归一化 title_key（繁转简 + 去标点括号）跨源去重：
 * - 底层保留各源记录（多线路不丢失）；
 * - 索引展示按阅读完整度优选主源（copymanga > tencent > bilibili），并取跨源标签并集、最高置信度地区、
 *   最高真实评分与最高推荐分。
 */
@Repository
public class MangaCatalogRepository {

    private static final String GROUP_KEY_EXPR = "COALESCE(NULLIF(title_key, ''), source || ':' || source_item_id)";
    private static final String GROUP_KEY_EXPR_M = "COALESCE(NULLIF(m.title_key, ''), m.source || ':' || m.source_item_id)";

    private static final String DEDUP_CTE = """
            WITH base AS (
                SELECT DISTINCT ON (%1$s)
                       %1$s AS group_key,
                       id, source, source_item_id, name, alt_title, cover_url, remarks,
                       episode_count, synced_at, first_seen_at, title_key
                  FROM manga_catalog_items
                 ORDER BY %1$s,
                          CASE source
                              WHEN 'copymanga' THEN 0
                              WHEN 'tencent' THEN 1
                              WHEN 'bilibili' THEN 2
                              ELSE 3
                          END ASC,
                          (cover_url <> '') DESC,
                          COALESCE(episode_count, NULLIF(substring(remarks FROM '([0-9]+)'), '')::int, 0) DESC,
                          synced_at DESC
            ),
            best_reg AS (
                SELECT DISTINCT ON (%1$s)
                       %1$s AS group_key,
                       region, region_rank
                  FROM manga_catalog_items
                 ORDER BY %1$s,
                          region_confidence DESC,
                          region_rank ASC,
                          synced_at DESC
            ),
            agg AS (
                SELECT %2$s AS group_key,
                       COALESCE(array_agg(DISTINCT u.t ORDER BY u.t) FILTER (WHERE u.t IS NOT NULL AND u.t <> ''), '{}'::text[]) AS tags,
                       MAX(COALESCE(m.episode_count, NULLIF(substring(m.remarks FROM '([0-9]+)'), '')::int)) AS best_ep,
                       MAX(NULLIF(m.remarks, '')) AS best_remarks,
                       MAX(m.rating) AS best_rating,
                       MAX(m.bayesian_rating) AS best_bayesian_rating,
                       MAX(m.recommend_score) AS best_recommend_score,
                       MAX(m.synced_at) AS latest_synced_at
                  FROM manga_catalog_items m
                  LEFT JOIN LATERAL unnest(m.tags) AS u(t) ON TRUE
                 GROUP BY 1
            ),
            deduped AS (
                SELECT b.id, b.source, b.source_item_id, b.name, b.alt_title, b.cover_url,
                       COALESCE(NULLIF(b.remarks, ''), a.best_remarks, '') AS remarks,
                       a.tags,
                       COALESCE(b.episode_count, a.best_ep) AS episode_count,
                       a.latest_synced_at AS synced_at,
                       b.first_seen_at, b.title_key,
                       br.region, br.region_rank,
                       a.best_rating::double precision AS rating,
                       a.best_bayesian_rating AS bayesian_rating,
                       a.best_recommend_score AS recommend_score
                  FROM base b
                  JOIN best_reg br ON br.group_key = b.group_key
                  JOIN agg a ON a.group_key = b.group_key
            )
            """.formatted(GROUP_KEY_EXPR, GROUP_KEY_EXPR_M);

    private final JdbcTemplate jdbc;
    /** text[] → List<String> 需手动转换（DataClassRowMapper 不支持 PgArray）。 */
    private final RowMapper<MangaCatalogItem> mapper = (rs, i) -> {
        var array = rs.getArray("tags");
        List<String> tags = array == null ? List.of()
                : List.of((String[]) array.getArray());
        Number ratingNum = (Number) rs.getObject("rating");
        Double rating = ratingNum == null ? null : ratingNum.doubleValue();
        return new MangaCatalogItem(
                rs.getObject("id", UUID.class), rs.getString("source"),
                rs.getString("source_item_id"), rs.getString("name"),
                rs.getString("alt_title"), rs.getString("cover_url"), rs.getString("remarks"),
                tags, (Integer) rs.getObject("episode_count"),
                rs.getObject("synced_at", OffsetDateTime.class).toInstant(),
                rs.getObject("first_seen_at", OffsetDateTime.class).toInstant(),
                rating, rs.getString("region"));
    };

    public MangaCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final java.util.regex.Pattern EPISODE_NUM = java.util.regex.Pattern.compile("(\\d{1,5})");

    private static Integer resolveEpisodeCount(Integer episodeCount, String remarks) {
        if (episodeCount != null && episodeCount > 0) return episodeCount;
        if (remarks == null || remarks.isBlank()) return null;
        var m = EPISODE_NUM.matcher(remarks);
        if (!m.find()) return null;
        try {
            int n = Integer.parseInt(m.group(1));
            return n > 0 ? n : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isSingleSource(String source) {
        return source != null && !source.isBlank() && !"all".equalsIgnoreCase(source);
    }

    /** upsert（基础重载）：使用源默认地区先验，不覆盖已有高置信度地区与热度。 */
    public void upsert(UUID id, String source, String sourceItemId, String name, String altTitle,
                       String coverUrl, String remarks, List<String> tags, Integer episodeCount) {
        CatalogRanking.RegionInfo reg = CatalogRanking.defaultMangaRegionForSource(source);
        upsert(id, source, sourceItemId, name, altTitle, coverUrl, remarks, tags, episodeCount,
                reg.region(), reg.confidence(), 0.0);
    }

    /** upsert（含地区置信度与源站归一化人气分）：高置信度地区不被低置信度覆盖，热度取历史最大值。 */
    public void upsert(UUID id, String source, String sourceItemId, String name, String altTitle,
                       String coverUrl, String remarks, List<String> tags, Integer episodeCount,
                       String region, int regionConfidence, double popularityScore) {
        List<String> cleanTags = TagNormalizer.normalize(tags);
        Integer resolvedEpisodes = resolveEpisodeCount(episodeCount, remarks);
        String titleKey = MangaMatcher.titleKey(name, source, sourceItemId);
        String normRegion = CatalogRanking.normalizeRegionValue(region);
        int regionRank = CatalogRanking.regionRank(normRegion);
        int safeConf = CatalogRanking.REGION_UNKNOWN.equals(normRegion) ? 0 : Math.max(0, regionConfidence);
        double safePop = Math.min(100.0, Math.max(0.0, popularityScore));
        jdbc.update("""
                INSERT INTO manga_catalog_items
                    (id, source, source_item_id, name, alt_title, cover_url, remarks, tags, episode_count, title_key,
                     region, region_rank, region_confidence, popularity_score)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, source_item_id) DO UPDATE SET
                    name = EXCLUDED.name,
                    alt_title = CASE WHEN EXCLUDED.alt_title <> '' THEN EXCLUDED.alt_title ELSE manga_catalog_items.alt_title END,
                    cover_url = CASE WHEN EXCLUDED.cover_url <> '' THEN EXCLUDED.cover_url ELSE manga_catalog_items.cover_url END,
                    remarks = CASE WHEN EXCLUDED.remarks <> '' THEN EXCLUDED.remarks ELSE manga_catalog_items.remarks END,
                    tags = CASE
                        WHEN cardinality(EXCLUDED.tags) > 0 THEN
                            ARRAY(SELECT DISTINCT t FROM unnest(manga_catalog_items.tags || EXCLUDED.tags) t WHERE t <> '' ORDER BY t)
                        ELSE manga_catalog_items.tags
                    END,
                    episode_count = GREATEST(COALESCE(EXCLUDED.episode_count, 0), COALESCE(manga_catalog_items.episode_count, 0))
                        * CASE WHEN EXCLUDED.episode_count IS NULL AND manga_catalog_items.episode_count IS NULL THEN NULL ELSE 1 END,
                    title_key = CASE WHEN EXCLUDED.title_key <> '' THEN EXCLUDED.title_key ELSE manga_catalog_items.title_key END,
                    region = CASE WHEN EXCLUDED.region_confidence >= manga_catalog_items.region_confidence
                                       AND EXCLUDED.region <> 'UNKNOWN'
                                  THEN EXCLUDED.region ELSE manga_catalog_items.region END,
                    region_rank = CASE WHEN EXCLUDED.region_confidence >= manga_catalog_items.region_confidence
                                            AND EXCLUDED.region <> 'UNKNOWN'
                                       THEN EXCLUDED.region_rank ELSE manga_catalog_items.region_rank END,
                    region_confidence = GREATEST(manga_catalog_items.region_confidence, EXCLUDED.region_confidence),
                    popularity_score = GREATEST(manga_catalog_items.popularity_score, EXCLUDED.popularity_score),
                    synced_at = now()
                """, id, source, sourceItemId, name, altTitle == null ? "" : altTitle,
                coverUrl == null ? "" : coverUrl, remarks == null ? "" : remarks,
                cleanTags.toArray(new String[0]), resolvedEpisodes, titleKey,
                normRegion, regionRank, safeConf, safePop);
    }

    public record MangaCatalogItem(UUID id, String source, String sourceItemId, String name,
                                   String altTitle, String coverUrl, String remarks,
                                   List<String> tags, Integer episodeCount,
                                   Instant syncedAt, Instant firstSeenAt,
                                   Double rating, String region) {
        public MangaCatalogItem(UUID id, String source, String sourceItemId, String name,
                                String altTitle, String coverUrl, String remarks,
                                List<String> tags, Integer episodeCount,
                                Instant syncedAt, Instant firstSeenAt) {
            this(id, source, sourceItemId, name, altTitle, coverUrl, remarks,
                    tags, episodeCount, syncedAt, firstSeenAt, null, null);
        }
    }

    /** 索引页分页：单源直查，全源按 title_key 跨源去重选主卡并合并标签、地区与评分。 */
    public List<MangaCatalogItem> page(String source, String genre, String status, String q,
                                       Instant beforeSynced, UUID beforeId, int limit) {
        return page(source, genre, status, q, null, "updated", beforeSynced, beforeId, null, limit);
    }

    public List<MangaCatalogItem> page(String source, String genre, String status, String q,
                                       String sort, Instant beforeSynced, UUID beforeId,
                                       Integer offset, int limit) {
        return page(source, genre, status, q, null, sort, beforeSynced, beforeId, offset, limit);
    }

    public List<MangaCatalogItem> page(String source, String genre, String status, String q,
                                       String region, String sort, Instant beforeSynced, UUID beforeId,
                                       Integer offset, int limit) {
        boolean singleSource = isSingleSource(source);
        StringBuilder where = new StringBuilder(" WHERE TRUE ");
        List<Object> args = new ArrayList<>();
        appendFilters(where, args, singleSource ? source : null, genre, status, q, region);
        String effectiveSort = (sort == null || sort.isBlank()) ? "recommended" : sort.trim().toLowerCase();
        String prefix = singleSource ? "" : DEDUP_CTE;
        String table = singleSource ? "manga_catalog_items" : "deduped";
        String ratingCol = singleSource ? "rating::double precision AS rating" : "rating";

        boolean useCursor = offset == null && beforeSynced != null && beforeId != null && "updated".equals(effectiveSort);
        if (useCursor) {
            where.append(" AND (synced_at, id) < (?, ?) ");
            args.add(OffsetDateTime.ofInstant(beforeSynced, java.time.ZoneOffset.UTC));
            args.add(beforeId);
            args.add(limit);
            return jdbc.query("""
                    %s
                    SELECT id, source, source_item_id, name, alt_title, cover_url, remarks,
                           tags, episode_count, synced_at, first_seen_at, %s, region
                      FROM %s
                    %s
                    ORDER BY synced_at DESC, id DESC
                    LIMIT ?
                    """.formatted(prefix, ratingCol, table, where), mapper, args.toArray());
        }

        String orderBy = switch (effectiveSort) {
            case "rating" -> """
                     ORDER BY bayesian_rating DESC NULLS LAST,
                              region_rank ASC,
                              recommend_score DESC,
                              synced_at DESC,
                              id DESC
                    """;
            case "updated" -> """
                     ORDER BY DATE(synced_at AT TIME ZONE 'Asia/Shanghai') DESC,
                              region_rank ASC,
                              synced_at DESC,
                              id DESC
                    """;
            default -> """
                     ORDER BY region_rank ASC,
                              recommend_score DESC,
                              synced_at DESC,
                              id DESC
                    """;
        };
        args.add(Math.max(offset == null ? 0 : offset, 0));
        args.add(limit);
        return jdbc.query("""
                %s
                SELECT id, source, source_item_id, name, alt_title, cover_url, remarks,
                       tags, episode_count, synced_at, first_seen_at, %s, region
                  FROM %s
                %s
                %s
                OFFSET ? LIMIT ?
                """.formatted(prefix, ratingCol, table, where, orderBy), mapper, args.toArray());
    }

    public long count(String source, String genre, String status, String q) {
        return count(source, genre, status, q, null);
    }

    public long count(String source, String genre, String status, String q, String region) {
        boolean singleSource = isSingleSource(source);
        boolean hasFilter = (genre != null && !genre.isBlank())
                || (status != null && !status.isBlank())
                || (q != null && !q.isBlank())
                || (region != null && !region.isBlank());
        if (!singleSource && !hasFilter) {
            Long total = jdbc.queryForObject(
                    "SELECT count(DISTINCT %s) FROM manga_catalog_items".formatted(GROUP_KEY_EXPR),
                    Long.class);
            return total == null ? 0L : total;
        }
        StringBuilder where = new StringBuilder(" WHERE TRUE ");
        List<Object> args = new ArrayList<>();
        appendFilters(where, args, singleSource ? source : null, genre, status, q, region);
        String prefix = singleSource ? "" : DEDUP_CTE;
        String table = singleSource ? "manga_catalog_items" : "deduped";
        Long total = jdbc.queryForObject(
                "%s SELECT count(*) FROM %s %s".formatted(prefix, table, where),
                Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /** 目录库搜索（支持简体搜繁体，供全流漫画搜索兜底无实时搜索接口的源与历史沉淀条目）。 */
    public List<MangaCatalogItem> search(String q, String source, int limit) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        String trimmed = q.trim();
        String escaped = escapeLike(trimmed);
        String normKey = MangaMatcher.normalize(trimmed);
        String escapedKey = escapeLike(normKey);
        StringBuilder sql = new StringBuilder("""
                SELECT id, source, source_item_id, name, alt_title, cover_url, remarks,
                       tags, episode_count, synced_at, first_seen_at,
                       rating::double precision AS rating, region
                  FROM manga_catalog_items
                 WHERE (name ILIKE ? ESCAPE '\\' OR alt_title ILIKE ? ESCAPE '\\'
                """);
        List<Object> args = new ArrayList<>();
        args.add("%" + escaped + "%");
        args.add("%" + escaped + "%");
        if (!normKey.isEmpty()) {
            sql.append(" OR title_key ILIKE ? ESCAPE '\\' ");
            args.add("%" + escapedKey + "%");
        }
        sql.append(") ");
        if (isSingleSource(source)) {
            sql.append(" AND source = ? ");
            args.add(source);
        }
        sql.append("""
                 ORDER BY CASE
                            WHEN lower(name) = lower(?) OR (title_key <> '' AND title_key = ?) THEN 0
                            WHEN name ILIKE ? ESCAPE '\\' THEN 1
                            ELSE 2
                          END,
                          region_rank ASC,
                          recommend_score DESC,
                          synced_at DESC
                 LIMIT ?
                """);
        args.add(trimmed);
        args.add(normKey);
        args.add(escaped + "%");
        args.add(Math.min(Math.max(limit, 1), 60));
        return jdbc.query(sql.toString(), mapper, args.toArray());
    }

    /** 列出最近同步的目录条目（供推荐候选合并）。 */
    public List<MangaCatalogItem> recent(int limit) {
        return jdbc.query("""
                SELECT id, source, source_item_id, name, alt_title, cover_url, remarks,
                       tags, episode_count, synced_at, first_seen_at,
                       rating::double precision AS rating, region
                  FROM manga_catalog_items
                 ORDER BY region_rank ASC, recommend_score DESC, synced_at DESC, id DESC
                 LIMIT ?
                """, mapper, Math.min(Math.max(limit, 1), 300));
    }

    private static void appendFilters(StringBuilder where, List<Object> args,
                                      String source, String genre, String status, String q, String region) {
        if (source != null && !source.isBlank() && !"all".equalsIgnoreCase(source)) {
            where.append(" AND source = ? ");
            args.add(source);
        }
        String normRegion = CatalogRanking.normalizeRegionFilter(region);
        if (normRegion != null) {
            where.append(" AND region = ? ");
            args.add(normRegion);
        }
        if (genre != null && !genre.isBlank()) {
            where.append(" AND tags && ?::text[] ");
            args.add(TagNormalizer.aliasesFor(genre));
        }
        if (status != null && !status.isBlank()) {
            switch (status) {
                case "completed" -> where.append(" AND (remarks LIKE '%完结%' OR remarks LIKE '%全%') AND remarks NOT LIKE '更新至%' ");
                case "ongoing" -> where.append(" AND (remarks LIKE '更新%' OR remarks LIKE '%连载%') ");
                default -> throw new IllegalArgumentException("未知状态筛选：" + status);
            }
        }
        if (q != null && !q.isBlank()) {
            String escaped = escapeLike(q.trim());
            String normKey = MangaMatcher.normalize(q.trim());
            if (!normKey.isEmpty()) {
                where.append(" AND (name ILIKE ? ESCAPE '\\' OR alt_title ILIKE ? ESCAPE '\\' OR title_key ILIKE ? ESCAPE '\\') ");
                args.add("%" + escaped + "%");
                args.add("%" + escaped + "%");
                args.add("%" + escapeLike(normKey) + "%");
            } else {
                where.append(" AND (name ILIKE ? ESCAPE '\\' OR alt_title ILIKE ? ESCAPE '\\') ");
                args.add("%" + escaped + "%");
                args.add("%" + escaped + "%");
            }
        }
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 题材聚合：全源按去重作品数 count(DISTINCT title_key) 计频，避免三源重复虚高热度。 */
    public record GenreTag(String tag, long items) {}

    public List<GenreTag> genres(String source) {
        boolean filterSource = isSingleSource(source);
        String sql = filterSource ? """
                SELECT t AS tag, count(*) AS items
                  FROM manga_catalog_items, unnest(tags) t
                 WHERE source = ?
                   AND t <> ''
                   AND t NOT LIKE '更新%'
                   AND t NOT LIKE '第%话%'
                   AND length(t) <= 12
                 GROUP BY t
                 ORDER BY count(*) DESC, t ASC
                 LIMIT 120
                """ : """
                SELECT t AS tag, count(DISTINCT %s) AS items
                  FROM manga_catalog_items, unnest(tags) t
                 WHERE t <> ''
                   AND t NOT LIKE '更新%%'
                   AND t NOT LIKE '第%%话%%'
                   AND length(t) <= 12
                 GROUP BY t
                 ORDER BY items DESC, t ASC
                 LIMIT 120
                """.formatted(GROUP_KEY_EXPR);
        List<GenreTag> raw = filterSource
                ? jdbc.query(sql, (rs, i) -> new GenreTag(rs.getString("tag"), rs.getLong("items")), source)
                : jdbc.query(sql, (rs, i) -> new GenreTag(rs.getString("tag"), rs.getLong("items")));
        var merged = new java.util.LinkedHashMap<String, Long>();
        for (GenreTag g : raw) {
            String canonical = TagNormalizer.canonicalize(g.tag());
            if (canonical != null) {
                merged.merge(canonical, g.items(), Long::sum);
            }
        }
        List<GenreTag> filtered = merged.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted(java.util.Comparator.<java.util.Map.Entry<String, Long>>comparingLong(java.util.Map.Entry::getValue)
                        .reversed().thenComparing(java.util.Map.Entry::getKey))
                .limit(30)
                .map(e -> new GenreTag(e.getKey(), e.getValue()))
                .toList();
        if (!filtered.isEmpty()) {
            return filtered;
        }
        return merged.entrySet().stream()
                .sorted(java.util.Comparator.<java.util.Map.Entry<String, Long>>comparingLong(java.util.Map.Entry::getValue)
                        .reversed().thenComparing(java.util.Map.Entry::getKey))
                .limit(30)
                .map(e -> new GenreTag(e.getKey(), e.getValue()))
                .toList();
    }

    public record EditorPickItem(UUID id, String source, String sourceItemId, String name,
                                 String altTitle, String coverUrl, String remarks,
                                 List<String> tags, Integer episodeCount,
                                 Double rating, String region, int sourceCount) {}

    /**
     * 全站口碑精选（编辑精选）：
     * 直接基于本地去重目录库，按「日本 → 国产 → 韩国」均衡配额（约 55% / 22% / 23%）
     * 筛选高贝叶斯评分（真实 ★ 评分 ≥ 7.0 优先）+ 多源共识 + 高推荐分佳作，零外网依赖。
     */
    public List<EditorPickItem> editorPicks(int limit) {
        int size = Math.min(Math.max(limit, 1), 24);
        int jpQuota = Math.max(1, Math.round(size * 0.55f));
        int cnQuota = Math.max(1, Math.round(size * 0.22f));
        int krQuota = Math.max(1, size - jpQuota - cnQuota);

        String sql = """
                WITH base AS (
                    SELECT DISTINCT ON (%1$s)
                           %1$s AS group_key,
                           id, source, source_item_id, name, alt_title, cover_url, remarks,
                           episode_count, synced_at, first_seen_at
                      FROM manga_catalog_items
                     ORDER BY %1$s,
                              CASE source
                                  WHEN 'copymanga' THEN 0
                                  WHEN 'tencent' THEN 1
                                  WHEN 'bilibili' THEN 2
                                  ELSE 3
                              END ASC,
                              (cover_url <> '') DESC,
                              COALESCE(episode_count, NULLIF(substring(remarks FROM '([0-9]+)'), '')::int, 0) DESC,
                              synced_at DESC
                ),
                best_reg AS (
                    SELECT DISTINCT ON (%1$s)
                           %1$s AS group_key,
                           region, region_rank
                      FROM manga_catalog_items
                     ORDER BY %1$s,
                              region_confidence DESC,
                              region_rank ASC,
                              synced_at DESC
                ),
                agg AS (
                    SELECT %2$s AS group_key,
                           COUNT(DISTINCT m.source)::int AS source_count,
                           COALESCE(array_agg(DISTINCT u.t ORDER BY u.t) FILTER (WHERE u.t IS NOT NULL AND u.t <> ''), '{}'::text[]) AS tags,
                           MAX(COALESCE(m.episode_count, NULLIF(substring(m.remarks FROM '([0-9]+)'), '')::int)) AS best_ep,
                           MAX(NULLIF(m.remarks, '')) AS best_remarks,
                           MAX(m.rating) AS best_rating,
                           MAX(m.bayesian_rating) AS best_bayesian_rating,
                           MAX(m.recommend_score) AS best_recommend_score
                      FROM manga_catalog_items m
                      LEFT JOIN LATERAL unnest(m.tags) AS u(t) ON TRUE
                     GROUP BY 1
                ),
                pool AS (
                    SELECT b.id, b.source, b.source_item_id, b.name, b.alt_title, b.cover_url,
                           COALESCE(NULLIF(b.remarks, ''), a.best_remarks, '') AS remarks,
                           a.tags,
                           COALESCE(b.episode_count, a.best_ep) AS episode_count,
                           a.best_rating::double precision AS rating,
                           a.best_bayesian_rating AS bayesian_rating,
                           a.best_recommend_score AS recommend_score,
                           br.region, br.region_rank,
                           a.source_count,
                           ROW_NUMBER() OVER (
                               PARTITION BY br.region
                               ORDER BY (a.best_bayesian_rating IS NOT NULL AND a.best_rating >= 7.0) DESC,
                                        a.best_bayesian_rating DESC NULLS LAST,
                                        a.source_count DESC,
                                        a.best_recommend_score DESC,
                                        b.id DESC
                           ) AS rn
                      FROM base b
                      JOIN best_reg br ON br.group_key = b.group_key
                      JOIN agg a ON a.group_key = b.group_key
                     WHERE b.cover_url <> ''
                       AND br.region IN ('JP', 'CN', 'KR')
                ),
                ranked AS (
                    SELECT *,
                           CASE
                               WHEN (region = 'JP' AND rn <= ?)
                                 OR (region = 'CN' AND rn <= ?)
                                 OR (region = 'KR' AND rn <= ?)
                               THEN 0 ELSE 1
                           END AS pick_tier
                      FROM pool
                ),
                picked AS (
                    SELECT *
                      FROM ranked
                     ORDER BY pick_tier ASC,
                              region_rank ASC,
                              bayesian_rating DESC NULLS LAST,
                              source_count DESC,
                              recommend_score DESC,
                              id DESC
                     LIMIT ?
                )
                SELECT id, source, source_item_id, name, alt_title, cover_url, remarks,
                       tags, episode_count, rating, region, source_count
                  FROM picked
                 ORDER BY region_rank ASC,
                          bayesian_rating DESC NULLS LAST,
                          source_count DESC,
                          recommend_score DESC,
                          id DESC
                """.formatted(GROUP_KEY_EXPR, GROUP_KEY_EXPR_M);

        return jdbc.query(sql, (rs, i) -> {
            var array = rs.getArray("tags");
            List<String> tags = array == null ? List.of() : List.of((String[]) array.getArray());
            Number ratingNum = (Number) rs.getObject("rating");
            return new EditorPickItem(
                    rs.getObject("id", UUID.class),
                    rs.getString("source"),
                    rs.getString("source_item_id"),
                    rs.getString("name"),
                    rs.getString("alt_title"),
                    rs.getString("cover_url"),
                    rs.getString("remarks"),
                    tags,
                    (Integer) rs.getObject("episode_count"),
                    ratingNum == null ? null : ratingNum.doubleValue(),
                    rs.getString("region"),
                    rs.getInt("source_count"));
        }, jpQuota, cnQuota, krQuota, size);
    }

    public Optional<MangaCatalogItem> find(String source, String sourceItemId) {
        return jdbc.query("""
                SELECT id, source, source_item_id, name, alt_title, cover_url, remarks,
                       tags, episode_count, synced_at, first_seen_at,
                       rating::double precision AS rating, region
                  FROM manga_catalog_items
                 WHERE source = ? AND source_item_id = ?
                """, mapper, source, sourceItemId).stream().findFirst();
    }
}
