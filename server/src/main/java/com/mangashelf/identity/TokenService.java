package com.mangashelf.identity;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 接入 Token 生命周期（DESIGN 17.1）：生成（32 字节熵）、SHA-256 入库、scope 校验。
 * 原始值仅创建时返回一次；日志与导出不包含 Token。
 */
@Service
public class TokenService {

    public static final String TOKEN_PREFIX = "ms_";
    public static final Duration DEFAULT_TTL = Duration.ofDays(90);

    private final ApiTokenRepository tokens;
    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();

    public TokenService(ApiTokenRepository tokens, UserRepository users) {
        this.tokens = tokens;
        this.users = users;
    }

    public record CreatedToken(ApiToken token, String rawToken) {
    }

    /** 创建 Token；scope 集合在端点层校验合法性与非空。 */
    public CreatedToken create(CurrentUser user, Set<String> scopes, Duration ttl) {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String raw = TOKEN_PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        String hash = sha256Hex(raw);
        UUID id = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(ttl);
        ApiToken token = new ApiToken(id, user.id(), hash, raw.substring(0, 12),
                String.join(",", scopes), null, expiresAt, null);
        tokens.insert(token);
        return new CreatedToken(token, raw);
    }

    public java.util.List<ApiToken> list(CurrentUser user) {
        return tokens.listByUser(user.id());
    }

    public boolean revoke(CurrentUser user, UUID tokenId) {
        return tokens.revoke(user.id(), tokenId);
    }

    /** Bearer 认证：哈希查找 + 未撤销未过期 + 用户 ACTIVE；不通过返回空。 */
    public TokenAuthentication authenticate(String rawToken) {
        ApiToken token = tokens.findByHash(sha256Hex(rawToken)).orElse(null);
        if (token == null || token.revokedAt() != null) {
            return null;
        }
        if (token.expiresAt() != null && token.expiresAt().isBefore(Instant.now())) {
            return null;
        }
        User user = users.findById(token.userId()).orElse(null);
        if (user == null || !User.STATUS_ACTIVE.equals(user.status())) {
            return null;
        }
        CurrentUser principal = new CurrentUser(user.id(), user.login(), user.role());
        Set<String> scopes = java.util.Arrays.stream(token.scopes().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        return new TokenAuthentication(principal, scopes);
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
