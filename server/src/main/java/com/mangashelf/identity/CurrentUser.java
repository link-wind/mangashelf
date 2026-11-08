package com.mangashelf.identity;

import java.io.Serializable;
import java.util.UUID;

/** 会话中的当前用户（principal）；不含密码哈希。须可序列化以存入 JDBC 会话。 */
public record CurrentUser(UUID id, String login, String role) implements Serializable {

    private static final long serialVersionUID = 1L;

    public boolean isAdmin() {
        return User.ROLE_ADMIN.equals(role);
    }
}
