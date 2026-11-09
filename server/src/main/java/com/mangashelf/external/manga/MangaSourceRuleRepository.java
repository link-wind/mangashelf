package com.mangashelf.external.manga;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 漫画规则源行（M23）：源 = 数据，新增中文源只加行不改代码。 */
@Repository
public class MangaSourceRuleRepository {

    public record RuleRow(String id, String name, String note, String rules, boolean enabled) {}

    private final JdbcTemplate jdbc;

    public MangaSourceRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RuleRow> findEnabled() {
        return jdbc.query("""
                SELECT id, name, note, rules::text AS rules, enabled
                FROM manga_source_rules WHERE enabled ORDER BY created_at
                """, (rs, i) -> new RuleRow(rs.getString("id"), rs.getString("name"),
                rs.getString("note"), rs.getString("rules"), rs.getBoolean("enabled")));
    }
}
