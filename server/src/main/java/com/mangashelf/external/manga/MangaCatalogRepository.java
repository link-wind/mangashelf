package com.mangashelf.external.manga;

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

    /** upsert：新条目记录 first_seen_at；已存在更新内容与 synced_at。 */
    public void upsert(UUID id, String source, String sourceItemId, String name, String altTitle,
                       String coverUrl, String remarks, List<String> tags, Integer episodeCount) {
        jdbc.update("""
                INSERT INTO manga_catalog_items
                    (id, source, source_item_id, name, alt_title, cover_url, remarks, tags, episode_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, source_item_id) DO UPDATE SET
                    name = EXCLUDED.name, alt_title = EXCLUDED.alt_title,
                    cover_url = EXCLUDED.cover_url, remarks = EXCLUDED.remarks,
                    tags = EXCLUDED.tags, episode_count = EXCLUDED.episode_count,
                    synced_at = now()
                """, id, source, sourceItemId, name, altTitle, coverUrl, remarks,
                tags.toArray(new String[0]), episodeCount);
    }

    public record MangaCatalogItem(UUID id, String source, String sourceItemId, String name,
                                   String altTitle, String coverUrl, String remarks,
                                   List<String> tags, Integer episodeCount,
                                   Instant syncedAt, Instant firstSeenAt) {}

    /** 索引页分页：键集 (synced_at, id) 倒序；题材/状态/标题过滤。 */
    public List<MangaCatalogItem> page(String source, String genre, String status, String q,
                                       Instant beforeSynced, UUID beforeId, int limit) {
        StringBuilder where = new StringBuilder(" WHERE source = ? ");
        List<Object> args = new java.util.ArrayList<>();
        args.add(source);
        if (genre != null && !genre.isBlank()) {
            where.append(" AND EXISTS (SELECT 1 FROM unnest(tags) t WHERE t = ?) ");
            args.add(genre);
        }
        if (status != null && !status.isBlank()) {
            switch (status) {
                case "completed" -> where.append(" AND remarks LIKE '%完结%' AND remarks NOT LIKE '更新至%' ");
                case "ongoing" -> where.append(" AND remarks LIKE '更新至%' ");
                default -> throw new IllegalArgumentException("未知状态筛选：" + status);
            }
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND name ILIKE ? ESCAPE '\\' ");
            args.add("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
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
        StringBuilder where = new StringBuilder(" WHERE source = ? ");
        List<Object> args = new java.util.ArrayList<>();
        args.add(source);
        if (genre != null && !genre.isBlank()) {
            where.append(" AND EXISTS (SELECT 1 FROM unnest(tags) t WHERE t = ?) ");
            args.add(genre);
        }
        if (status != null && !status.isBlank()) {
            switch (status) {
                case "completed" -> where.append(" AND remarks LIKE '%完结%' AND remarks NOT LIKE '更新至%' ");
                case "ongoing" -> where.append(" AND remarks LIKE '更新至%' ");
                default -> throw new IllegalArgumentException("未知状态筛选：" + status);
            }
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND name ILIKE ? ESCAPE '\\' ");
            args.add("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return jdbc.queryForObject(
                "SELECT count(*) FROM manga_catalog_items %s".formatted(where), Long.class, args.toArray());
    }

    /** 源内题材聚合：库内 tags 去重计频，按出现次数降序。 */
    public record GenreTag(String tag, long items) {}

    public List<GenreTag> genres(String source) {
        return jdbc.query("""
                SELECT t AS tag, count(*) AS items
                  FROM manga_catalog_items, unnest(tags) t
                 WHERE source = ?
                 GROUP BY t
                 ORDER BY count(*) DESC
                """, (rs, i) -> new GenreTag(rs.getString("tag"), rs.getLong("items")), source);
    }

    public Optional<MangaCatalogItem> find(String source, String sourceItemId) {
        return jdbc.query("SELECT * FROM manga_catalog_items WHERE source = ? AND source_item_id = ?",
                mapper, source, sourceItemId).stream().findFirst();
    }
}
