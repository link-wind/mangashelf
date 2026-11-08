package com.mangashelf.identity;

import com.mangashelf.config.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 个人接入 Token（DESIGN 16.1 Token / 17.1 scope）：原始值仅创建响应展示一次。 */
@RestController
@RequestMapping("/api/v1/me/api-tokens")
@Validated
public class TokenController {

    public record CreateTokenRequest(@NotEmpty Set<String> scopes,
                                     @Positive Long expiresInDays) {
    }

    public record TokenView(UUID id, String prefix, String scopes, String createdAt,
                            String expiresAt, String revokedAt) {
    }

    public record CreatedTokenView(TokenView token, String rawToken) {
    }

    private final TokenService tokens;

    public TokenController(TokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping
    public CreatedTokenView create(@AuthUser CurrentUser user,
                                   @Valid @RequestBody CreateTokenRequest request) {
        Set<String> scopes = request.scopes().stream()
                .filter(s -> !s.isBlank()).collect(Collectors.toSet());
        if (scopes.isEmpty() || !Scopes.ALL.containsAll(scopes)) {
            Set<String> illegal = new java.util.HashSet<>(scopes);
            illegal.removeAll(Scopes.ALL);
            throw new ApiException(400, "INVALID_SCOPES", "包含不支持的 scope：" + illegal);
        }
        Duration ttl = request.expiresInDays() == null
                ? TokenService.DEFAULT_TTL : Duration.ofDays(request.expiresInDays());
        TokenService.CreatedToken created = tokens.create(user, scopes, ttl);
        return new CreatedTokenView(toView(created.token()), created.rawToken());
    }

    @GetMapping
    public List<TokenView> list(@AuthUser CurrentUser user) {
        return tokens.list(user).stream().map(TokenController::toView).toList();
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> revoke(@AuthUser CurrentUser user, @PathVariable UUID id) {
        if (!tokens.revoke(user, id)) {
            throw new ApiException(404, "TOKEN_NOT_FOUND", "Token 不存在或已撤销。");
        }
        return Map.of("id", id, "revoked", true);
    }

    private static TokenView toView(ApiToken token) {
        return new TokenView(token.id(), token.prefix(), token.scopes(),
                String.valueOf(token.createdAt()), String.valueOf(token.expiresAt()),
                token.revokedAt() == null ? null : String.valueOf(token.revokedAt()));
    }
}
