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
                INSERT INTO users (id, login, password_hash, role, status, email, email_verified_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, user.id(), user.login(), user.passwordHash(), user.role(), user.status(),
                user.email(), JdbcTime.t(user.emailVerifiedAt()));
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

    public Optional<User> findByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        return jdbc.query("SELECT * FROM users WHERE lower(email) = lower(?)", mapper, email.trim())
                .stream().findFirst();
    }

    /** 用户名或邮箱二选一查询（登录支持直接用已绑定邮箱登录）。 */
    public Optional<User> findByLoginOrEmail(String identifier) {
        if (identifier == null || identifier.isBlank()) return Optional.empty();
        String trimmed = identifier.trim();
        if (trimmed.contains("@")) {
            return findByEmail(trimmed);
        }
        return findByLogin(trimmed);
    }

    public boolean existsByLogin(String login) {
        List<UUID> ids = jdbc.query("SELECT id FROM users WHERE login = ?",
                (rs, i) -> rs.getObject("id", UUID.class), login);
        return !ids.isEmpty();
    }

    public boolean existsByEmail(String email) {
        if (email == null || email.isBlank()) return false;
        List<UUID> ids = jdbc.query("SELECT id FROM users WHERE lower(email) = lower(?)",
                (rs, i) -> rs.getObject("id", UUID.class), email.trim());
        return !ids.isEmpty();
    }
}
