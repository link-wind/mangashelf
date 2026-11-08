package com.mangashelf.identity;

import java.time.Instant;
import java.util.UUID;

/** 用户（DESIGN 8.2 users）。role / status 由数据库 CHECK 限定。 */
public record User(
        UUID id,
        String login,
        String passwordHash,
        String role,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String STATUS_ACTIVE = "ACTIVE";
}
