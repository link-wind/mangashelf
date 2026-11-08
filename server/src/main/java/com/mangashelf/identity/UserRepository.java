package com.mangashelf.identity;

import com.mangashelf.config.JdbcTime;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<User> mapper = new DataClassRowMapper<>(User.class);

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(User user) {
        jdbc.update("""
                INSERT INTO users (id, login, password_hash, role, status)
                VALUES (?, ?, ?, ?, ?)
                """, user.id(), user.login(), user.passwordHash(), user.role(), user.status());
    }

    public void updatePasswordHash(UUID id, String passwordHash) {
        jdbc.update("UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?",
                passwordHash, JdbcTime.t(java.time.Instant.now()), id);
    }

    public Optional<User> findById(UUID id) {
        return jdbc.query("SELECT * FROM users WHERE id = ?", mapper, id).stream().findFirst();
    }

    public Optional<User> findByLogin(String login) {
        return jdbc.query("SELECT * FROM users WHERE login = ?", mapper, login).stream().findFirst();
    }

    public boolean existsByLogin(String login) {
        List<UUID> ids = jdbc.query("SELECT id FROM users WHERE login = ?",
                (rs, i) -> rs.getObject("id", UUID.class), login);
        return !ids.isEmpty();
    }
}
