package com.mangashelf.external;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ExternalRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<ExternalSource> sourceMapper = new DataClassRowMapper<>(ExternalSource.class);
    private final RowMapper<ExternalEpisode> episodeMapper = new DataClassRowMapper<>(ExternalEpisode.class);

    public ExternalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── 规则源 ──

    public UUID insertSource(String name, String baseUrl, String rules, boolean enabled) {
        return insertSource(name, baseUrl, rules, enabled, "");
    }

    public UUID insertSource(String name, String baseUrl, String rules, boolean enabled, String cookie) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO external_sources (id, name, base_url, rules, enabled, cookie)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)
                """, id, name, baseUrl, rules, enabled, cookie);
        return id;
    }

    public Optional<ExternalSource> findSource(UUID id) {
        return jdbc.query("SELECT * FROM external_sources WHERE id = ?", sourceMapper, id).stream().findFirst();
    }

    public Optional<ExternalEpisode> findEpisode(UUID id) {
        return jdbc.query("SELECT * FROM external_episodes WHERE id = ?", episodeMapper, id).stream().findFirst();
    }

    /** 解析结果落外部集（design.md D2：领域状态在集上行，不在任务表）。 */
    public void markResolved(UUID episodeId, String playUrl, String playKind) {
        jdbc.update("UPDATE external_episodes SET resolved_url = ?, resolved_kind = ?, resolved_at = now() WHERE id = ?",
                playUrl, playKind, episodeId);
    }

    /** 最新一次解析任务（含终态），供 resolve 状态查询与 30 秒窗口幂等复用。 */
    public Optional<com.mangashelf.ingestion.Job> findLatestResolveJob(UUID episodeId) {
        return jdbc.query("""
                SELECT * FROM jobs WHERE kind = ? AND external_episode_id = ?
                ORDER BY created_at DESC LIMIT 1
                """, new DataClassRowMapper<>(com.mangashelf.ingestion.Job.class),
                com.mangashelf.ingestion.Job.KIND_EXTERNAL_RESOLVE, episodeId).stream().findFirst();
    }

    public List<ExternalSource> listSources(boolean enabledOnly) {
        String sql = enabledOnly
                ? "SELECT * FROM external_sources WHERE enabled = true ORDER BY name"
                : "SELECT * FROM external_sources ORDER BY name";
        return jdbc.query(sql, sourceMapper);
    }

    public boolean setSourceEnabled(UUID id, boolean enabled) {
        return jdbc.update("UPDATE external_sources SET enabled = ?, updated_at = now() WHERE id = ?",
                enabled, id) > 0;
    }

    public boolean deleteSource(UUID id) {
        return jdbc.update("DELETE FROM external_sources WHERE id = ?", id) > 0;
    }

    // ── 外部集 ──

    public void insertEpisode(UUID id, UUID workId, UUID sourceId, String title,
                              String episodeUrl, int ordinal) {
        insertEpisode(id, workId, sourceId, title, episodeUrl, ordinal, "", 0, "MAIN");
    }

    public void insertEpisode(UUID id, UUID workId, UUID sourceId, String title,
                              String episodeUrl, int ordinal, String lineName, int lineRank,
                              String episodeType) {
        jdbc.update("""
                INSERT INTO external_episodes
                    (id, work_id, source_id, title, episode_url, ordinal, line_name, line_rank, episode_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (work_id, source_id, line_name, ordinal)
                    WHERE work_id IS NOT NULL DO UPDATE
                SET title = EXCLUDED.title, episode_url = EXCLUDED.episode_url,
                    line_rank = EXCLUDED.line_rank, episode_type = EXCLUDED.episode_type
                """, id, workId, sourceId, title, episodeUrl, ordinal, lineName, lineRank, episodeType);
    }

    /** 浏览态：把剧集挂到目录条目上（不建作品）。 */
    public void insertCatalogEpisode(UUID id, UUID catalogItemId, UUID sourceId, String title,
                                     String episodeUrl, int ordinal, String lineName, int lineRank,
                                     String episodeType) {
        jdbc.update("""
                INSERT INTO external_episodes
                    (id, catalog_item_id, source_id, title, episode_url, ordinal,
                     line_name, line_rank, episode_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (catalog_item_id, source_id, line_name, ordinal)
                    WHERE catalog_item_id IS NOT NULL DO UPDATE
                SET title = EXCLUDED.title, episode_url = EXCLUDED.episode_url,
                    line_rank = EXCLUDED.line_rank, episode_type = EXCLUDED.episode_type
                """, id, catalogItemId, sourceId, title, episodeUrl, ordinal,
                lineName, lineRank, episodeType);
    }

    public List<ExternalEpisode> listEpisodesByCatalogItem(UUID catalogItemId) {
        return jdbc.query(
                "SELECT * FROM external_episodes WHERE catalog_item_id = ? ORDER BY line_rank, ordinal, line_name",
                episodeMapper, catalogItemId);
    }

    /** 入书库：把该目录条目的浏览态剧集收编到作品下。 */
    public int adoptEpisodes(UUID catalogItemId, UUID workId) {
        return jdbc.update("""
                UPDATE external_episodes SET work_id = ?, catalog_item_id = NULL
                WHERE catalog_item_id = ?
                """, workId, catalogItemId);
    }

    public List<ExternalEpisode> listEpisodes(UUID workId) {
        return jdbc.query(
                "SELECT * FROM external_episodes WHERE work_id = ? ORDER BY line_rank, ordinal, line_name",
                episodeMapper, workId);
    }

    public ExternalService.WorkExternalMeta workExternalMeta(UUID workId) {
        List<ExternalService.WorkExternalMeta> rows = jdbc.query("""
                SELECT c.cover_url, c.name AS catalog_title, b.rating, b.summary, b.air_weekday,
                       c.play_groups
                FROM external_catalog_items c
                LEFT JOIN bangumi_subjects b ON b.subject_id = c.bangumi_subject_id
                WHERE c.work_id = ?
                LIMIT 1
                """, (rs, i) -> {
            var rating = rs.getBigDecimal("rating");
            List<String> lines = new java.util.ArrayList<>();
            try {
                for (var g : new tools.jackson.databind.ObjectMapper()
                        .readTree(rs.getString("play_groups"))) {
                    lines.add(g.path("from").asText("线路"));
                }
            } catch (Exception ignored) {
                // 线路解析失败只影响展示
            }
            return new ExternalService.WorkExternalMeta(
                    rs.getString("cover_url"), rs.getString("catalog_title"),
                    rating == null ? null : rating.doubleValue(),
                    rs.getString("summary"), (Integer) rs.getObject("air_weekday"), lines);
        }, workId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── 外部集观看进度（M10）──

    public record WatchSnapshot(UUID workId, int ordinal, UUID episodeId, long positionMs,
                                Long durationMs, int version, Instant updatedAt) {}

    /** 版本条件写入：返回新版本；空表示版本不匹配或并发已创建（调用方按冲突处理）。 */
    public Optional<Integer> upsertProgress(UUID userId, UUID workId, int ordinal, UUID episodeId,
                                            long positionMs, Long durationMs, int expectedVersion) {
        List<Integer> versions;
        if (expectedVersion == 0) {
            versions = jdbc.query("""
                    INSERT INTO external_watch_progress
                        (user_id, work_id, ordinal, episode_id, position_ms, duration_ms, version)
                    VALUES (?, ?, ?, ?, ?, ?, 1)
                    ON CONFLICT (user_id, work_id, ordinal) DO NOTHING
                    RETURNING version
                    """, (rs, i) -> rs.getInt(1), userId, workId, ordinal, episodeId,
                    positionMs, durationMs);
        } else {
            versions = jdbc.query("""
                    UPDATE external_watch_progress
                       SET episode_id = ?, position_ms = ?, duration_ms = ?,
                           version = version + 1, updated_at = now()
                     WHERE user_id = ? AND work_id = ? AND ordinal = ? AND version = ?
                    RETURNING version
                    """, (rs, i) -> rs.getInt(1), episodeId, positionMs, durationMs,
                    userId, workId, ordinal, expectedVersion);
        }
        return versions.stream().findFirst();
    }

    public Optional<WatchSnapshot> findProgress(UUID userId, UUID workId, int ordinal) {
        return jdbc.query("""
                SELECT work_id, ordinal, episode_id, position_ms, duration_ms, version, updated_at
                  FROM external_watch_progress
                 WHERE user_id = ? AND work_id = ? AND ordinal = ?
                """, (rs, i) -> new WatchSnapshot(
                        rs.getObject("work_id", UUID.class), rs.getInt("ordinal"),
                        rs.getObject("episode_id", UUID.class), rs.getLong("position_ms"),
                        rs.getObject("duration_ms", Long.class), rs.getInt("version"),
                        rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()),
                userId, workId, ordinal).stream().findFirst();
    }

    public record ProgressRow(UUID workId, String workTitle, int ordinal, String episodeTitle,
                              UUID episodeId, String lineName, String coverUrl,
                              long positionMs, Long durationMs, int version, Instant updatedAt) {}

    public List<ProgressRow> recentProgress(UUID userId, int limit) {
        return jdbc.query("""
                SELECT p.work_id, w.title AS work_title, p.ordinal, e.title AS episode_title,
                       p.episode_id, e.line_name,
                       (SELECT c.cover_url FROM external_catalog_items c
                         WHERE c.work_id = p.work_id AND c.cover_url <> '' LIMIT 1) AS cover_url,
                       p.position_ms, p.duration_ms, p.version, p.updated_at
                FROM external_watch_progress p
                JOIN works w ON w.id = p.work_id
                LEFT JOIN external_episodes e ON e.id = p.episode_id
                WHERE p.user_id = ?
                ORDER BY p.updated_at DESC
                LIMIT ?
                """, (rs, i) -> new ProgressRow(
                        rs.getObject("work_id", UUID.class), rs.getString("work_title"),
                        rs.getInt("ordinal"), rs.getString("episode_title"),
                        rs.getObject("episode_id", UUID.class), rs.getString("line_name"),
                        rs.getString("cover_url"), rs.getLong("position_ms"),
                        rs.getObject("duration_ms", Long.class), rs.getInt("version"),
                        rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()),
                userId, limit);
    }

    public boolean deleteEpisode(UUID id, UUID userId) {
        return jdbc.update("""
                DELETE FROM external_episodes e
                USING works w
                WHERE e.work_id = w.id AND e.id = ? AND w.owner_id = ?
                """, id, userId) > 0;
    }
}
