package com.mangashelf.external;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO external_sources (id, name, base_url, rules, enabled) VALUES (?, ?, ?, ?::jsonb, ?)",
                id, name, baseUrl, rules, enabled);
        return id;
    }

    public Optional<ExternalSource> findSource(UUID id) {
        return jdbc.query("SELECT * FROM external_sources WHERE id = ?", sourceMapper, id).stream().findFirst();
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
        jdbc.update("""
                INSERT INTO external_episodes (id, work_id, source_id, title, episode_url, ordinal)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (work_id, source_id, ordinal) DO UPDATE
                SET title = EXCLUDED.title, episode_url = EXCLUDED.episode_url
                """, id, workId, sourceId, title, episodeUrl, ordinal);
    }

    public List<ExternalEpisode> listEpisodes(UUID workId) {
        return jdbc.query(
                "SELECT * FROM external_episodes WHERE work_id = ? ORDER BY source_id, ordinal",
                episodeMapper, workId);
    }

    public boolean deleteEpisode(UUID id, UUID userId) {
        return jdbc.update("""
                DELETE FROM external_episodes e
                USING works w
                WHERE e.work_id = w.id AND e.id = ? AND w.owner_id = ?
                """, id, userId) > 0;
    }
}
