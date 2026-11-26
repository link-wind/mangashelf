package com.mangashelf.external.manga;

import com.mangashelf.external.TagNormalizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 漫画目录（manga-catalog-sync）：全量/渐进式同步的条目，按 (source, source_item_id) 唯一。
 * 题材查询走 GIN(tags)；标题搜索走 trgm；索引页分页按 (source, synced_at) 键集。
 */
@Repository
public class MangaCatalogRepository {

    private final JdbcTemplate jdbc;
    /** text[] → List<String> 需手动转换（DataClassRowMapper 不支持 PgArray）。 */
    private final RowMapper<MangaCatalogItem> mapper = (rs, i) -> {
        var array = rs.getArray("tags");
        List<String> tags = array == null ? List.of()
                : List.of((String[]) array.getArray());
        return new MangaCatalogItem(
                rs.getObject("id", UUID.class), rs.getString("source"),
                rs.getString("source_item_id"), rs.getString("name"),
                rs.getString("alt_title"), rs.getString("cover_url"), rs.getString("remarks"),
                tags, (Integer) rs.getObject("episode_count"),
                rs.getObject("synced_at", OffsetDateTime.class).toInstant(),
                rs.getObject("first_seen_at", OffsetDateTime.class).toInstant());
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

    /** upsert：新条目记录 first_seen_at；已存在时以非空字段增量合并，题材标签做去重并集累加。 */
    public void upsert(UUID id, String source, String sourceItemId, String name, String altTitle,
                       String coverUrl, String remarks, List<String> tags, Integer episodeCount) {
        List<String> cleanTags = TagNormalizer.normalize(tags);
        Integer resolvedEpisodes = resolveEpisodeCount(episodeCount, remarks);
        jdbc.update("""
                INSERT INTO manga_catalog_items
                    (id, source, source_item_id, name, alt_title, cover_url, remarks, tags, episode_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    episode_count = COALESCE(EXCLUDED.episode_count, manga_catalog_items.episode_count),
                    synced_at = now()
                """, id, source, sourceItemId, name, altTitle == null ? "" : altTitle,
                coverUrl == null ? "" : coverUrl, remarks == null ? "" : remarks,
                cleanTags.toArray(new String[0]), resolvedEpisodes);
    }

    public record MangaCatalogItem(UUID id, String source, String sourceItemId, String name,
                                   String altTitle, String coverUrl, String remarks,
                                   List<String> tags, Integer episodeCount,
                                   Instant syncedAt, Instant firstSeenAt) {}

    /** 索引页分页：兼容旧游标分页，同时支持全流（source 为空/all）、排序与偏移页码分页。 */
    public List<MangaCatalogItem> page(String source, String genre, String status, String q,
                                       Instant beforeSynced, UUID beforeId, int limit) {
        return page(source, genre, status, q, "updated", beforeSynced, beforeId, null, limit);
    }

    public List<MangaCatalogItem> page(String source, String genre, String status, String q,
                                       String sort, Instant beforeSynced, UUID beforeId,
                                       Integer offset, int limit) {
        StringBuilder where = new StringBuilder(" WHERE TRUE ");
        List<Object> args = new java.util.ArrayList<>();
        appendFilters(where, args, source, genre, status, q);
        boolean byEpisodes = "episodes".equals(sort);
        if (offset != null || byEpisodes) {
            String orderBy = byEpisodes
                    ? " ORDER BY COALESCE(episode_count, NULLIF(substring(remarks FROM '([0-9]+)'), '')::int, 0) DESC, synced_at DESC, id DESC "
                    : " ORDER BY synced_at DESC, id DESC ";
            args.add(Math.max(offset == null ? 0 : offset, 0));
            args.add(limit);
            return jdbc.query("""
                    SELECT * FROM manga_catalog_items
                    %s
                    %s
                    OFFSET ? LIMIT ?
                    """.formatted(where, orderBy), mapper, args.toArray());
        }
        where.append(" AND (synced_at, id) < (?, ?) ");
        args.add(OffsetDateTime.ofInstant(beforeSynced, java.time.ZoneOffset.UTC));
        args.add(beforeId);
        args.add(limit);
        return jdbc.query("""
                SELECT * FROM manga_catalog_items
                %s
                ORDER BY synced_at DESC, id DESC
                LIMIT ?
                """.formatted(where), mapper, args.toArray());
    }

    public long count(String source, String genre, String status, String q) {
        StringBuilder where = new StringBuilder(" WHERE TRUE ");
        List<Object> args = new java.util.ArrayList<>();
        appendFilters(where, args, source, genre, status, q);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM manga_catalog_items %s".formatted(where), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /** 目录库搜索（供全流漫画搜索兜底无实时搜索接口的源与历史沉淀条目）。 */
    public List<MangaCatalogItem> search(String q, String source, int limit) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        String trimmed = q.trim();
        String escaped = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM manga_catalog_items
                 WHERE (name ILIKE ? ESCAPE '\\' OR alt_title ILIKE ? ESCAPE '\\')
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add("%" + escaped + "%");
        args.add("%" + escaped + "%");
        if (source != null && !source.isBlank() && !"all".equalsIgnoreCase(source)) {
            sql.append(" AND source = ? ");
            args.add(source);
        }
        sql.append("""
                 ORDER BY CASE
                            WHEN lower(name) = lower(?) THEN 0
                            WHEN name ILIKE ? ESCAPE '\\' THEN 1
                            ELSE 2
                          END,
                          synced_at DESC
                 LIMIT ?
                """);
        args.add(trimmed);
        args.add(escaped + "%");
        args.add(Math.min(Math.max(limit, 1), 60));
        return jdbc.query(sql.toString(), mapper, args.toArray());
    }

    /** 列出最近同步的目录条目（供推荐候选合并）。 */
    public List<MangaCatalogItem> recent(int limit) {
        return jdbc.query("""
                SELECT * FROM manga_catalog_items
                 ORDER BY synced_at DESC, id DESC
                 LIMIT ?
                """, mapper, Math.min(Math.max(limit, 1), 300));
    }

    private static void appendFilters(StringBuilder where, List<Object> args,
                                      String source, String genre, String status, String q) {
        if (source != null && !source.isBlank() && !"all".equalsIgnoreCase(source)) {
            where.append(" AND source = ? ");
            args.add(source);
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
            String escaped = q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            where.append(" AND (name ILIKE ? ESCAPE '\\' OR alt_title ILIKE ? ESCAPE '\\') ");
            args.add("%" + escaped + "%");
            args.add("%" + escaped + "%");
        }
    }

    /** 题材聚合：库内 tags 去重计频（source 为空/all 时聚合全流），过滤话数噪声标签。 */
    public record GenreTag(String tag, long items) {}

    public List<GenreTag> genres(String source) {
        boolean filterSource = source != null && !source.isBlank() && !"all".equalsIgnoreCase(source);
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
                 LIMIT 100
                """ : """
                SELECT t AS tag, count(*) AS items
                  FROM manga_catalog_items, unnest(tags) t
                 WHERE t <> ''
                   AND t NOT LIKE '更新%'
                   AND t NOT LIKE '第%话%'
                   AND length(t) <= 12
                 GROUP BY t
                 ORDER BY count(*) DESC, t ASC
                 LIMIT 100
                """;
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

    public Optional<MangaCatalogItem> find(String source, String sourceItemId) {
        return jdbc.query("SELECT * FROM manga_catalog_items WHERE source = ? AND source_item_id = ?",
                mapper, source, sourceItemId).stream().findFirst();
    }
}
