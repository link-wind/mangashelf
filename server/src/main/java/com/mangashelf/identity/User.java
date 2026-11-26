package com.mangashelf.identity;

import java.time.Instant;
import java.util.UUID;

/** 用户（DESIGN 8.2 users）。role / status 由数据库 CHECK 限定；email 可为空以兼容存量账号。 */
public record User(
        UUID id,
        String login,
        String passwordHash,
        String role,
        String status,
        Instant createdAt,
        Instant updatedAt,
        String email,
        Instant emailVerifiedAt) {

    public User(UUID id, String login, String passwordHash, String role, String status,
                Instant createdAt, Instant updatedAt) {
        this(id, login, passwordHash, role, status, createdAt, updatedAt, null, null);
    }

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";
    /** 访客角色：仅用于未登录主体（CurrentUser.GUEST），不落库。 */
    public static final String ROLE_GUEST = "GUEST";
    public static final String STATUS_ACTIVE = "ACTIVE";
}
