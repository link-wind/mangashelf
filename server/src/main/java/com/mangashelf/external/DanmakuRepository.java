package com.mangashelf.external;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 站内弹幕（M13）：按 (work_id, ordinal) 取时间轴。 */
@Repository
public class DanmakuRepository {

    private final JdbcTemplate jdbc;

    public DanmakuRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Danmaku(UUID id, UUID workId, int ordinal, long timeMs,
                          String content, String color, String userName, Instant createdAt) {}

    public List<Danmaku> list(UUID workId, int ordinal) {
        return jdbc.query("""
                SELECT d.id, d.work_id, d.ordinal, d.time_ms, d.content, d.color,
                       u.login AS user_name, d.created_at
                FROM danmaku d JOIN users u ON u.id = d.user_id
                WHERE d.work_id = ? AND d.ordinal = ?
                ORDER BY d.time_ms ASC
                LIMIT 2000
                """, (rs, i) -> new Danmaku(
                        rs.getObject("id", UUID.class), rs.getObject("work_id", UUID.class),
                        rs.getInt("ordinal"), rs.getLong("time_ms"), rs.getString("content"),
                        rs.getString("color"), rs.getString("user_name"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()),
                workId, ordinal);
    }

    public Danmaku insert(UUID workId, int ordinal, UUID episodeId, UUID userId,
                          long timeMs, String content, String color) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO danmaku (id, work_id, ordinal, episode_id, user_id, time_ms, content, color)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, workId, ordinal, episodeId, userId, timeMs, content, color);
        return jdbc.query("""
                SELECT d.id, d.work_id, d.ordinal, d.time_ms, d.content, d.color,
                       u.login AS user_name, d.created_at
                FROM danmaku d JOIN users u ON u.id = d.user_id WHERE d.id = ?
                """, (rs, i) -> new Danmaku(
                        rs.getObject("id", UUID.class), rs.getObject("work_id", UUID.class),
                        rs.getInt("ordinal"), rs.getLong("time_ms"), rs.getString("content"),
                        rs.getString("color"), rs.getString("user_name"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()),
                id).stream().findFirst().orElseThrow();
    }
}
