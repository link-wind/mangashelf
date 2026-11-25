package com.mangashelf.identity;

import java.io.Serializable;
import java.util.UUID;

/** 会话中的当前用户（principal）；不含密码哈希。须可序列化以存入 JDBC 会话。 */
public record CurrentUser(UUID id, String login, String role) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 访客哨兵：未登录浏览公开内容时的代理主体。ID 全零，绝不匹配任何真实 owner_id。 */
    public static final CurrentUser GUEST =
            new CurrentUser(new UUID(0L, 0L), "guest", User.ROLE_GUEST);

    public boolean isAdmin() {
        return User.ROLE_ADMIN.equals(role);
    }

    public boolean isGuest() {
        return User.ROLE_GUEST.equals(role);
    }
}
