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
public class ApiTokenRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<ApiToken> mapper = new DataClassRowMapper<>(ApiToken.class);

    public ApiTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ApiToken token) {
        jdbc.update("""
                INSERT INTO api_tokens (id, user_id, token_hash, prefix, scopes, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, token.id(), token.userId(), token.tokenHash(), token.prefix(),
                token.scopes(), JdbcTime.t(token.expiresAt()));
    }

    public Optional<ApiToken> findByHash(String tokenHash) {
        return jdbc.query("SELECT * FROM api_tokens WHERE token_hash = ?", mapper, tokenHash)
                .stream().findFirst();
    }

    public List<ApiToken> listByUser(UUID userId) {
        return jdbc.query("SELECT * FROM api_tokens WHERE user_id = ? ORDER BY created_at DESC",
                mapper, userId);
    }

    /** 撤销置 revoked_at；返回 false 表示不存在或不属于该用户。 */
    public boolean revoke(UUID userId, UUID tokenId) {
        return jdbc.update("""
                UPDATE api_tokens SET revoked_at = now()
                WHERE id = ? AND user_id = ? AND revoked_at IS NULL
                """, tokenId, userId) > 0;
    }
}
