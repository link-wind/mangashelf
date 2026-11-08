package com.mangashelf.identity;

import java.time.Instant;
import java.util.UUID;

/** 接入 Token（DESIGN 8.2 api_tokens）：原始值仅创建时展示，库存 SHA-256。 */
public record ApiToken(
        UUID id,
        UUID userId,
        String tokenHash,
        String prefix,
        String scopes,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt) {
}
