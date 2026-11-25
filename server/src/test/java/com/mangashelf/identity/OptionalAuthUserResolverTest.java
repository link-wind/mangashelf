package com.mangashelf.identity;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 访客浏览（DESIGN 3.1/3.2）：未登录时 OptionalAuthUser 解析为 CurrentUser.GUEST（全零 ID），
 * 数据层 owner_id=全零 不匹配任何真实所有者，天然只返回 PUBLIC+PUBLISHED。已登录则注入真实用户。
 */
class OptionalAuthUserResolverTest {

    private final OptionalAuthUserResolver resolver = new OptionalAuthUserResolver();

    @Test
    void guestWhenUnauthenticated() throws Exception {
        SecurityContextHolder.clearContext();
        Object resolved = resolver.resolveArgument(null, null, null, null);
        assertSame(CurrentUser.GUEST, resolved);
        assertTrue(((CurrentUser) resolved).isGuest());
        assertEquals(new java.util.UUID(0L, 0L), ((CurrentUser) resolved).id());
        assertFalse(((CurrentUser) resolved).isAdmin());
    }

    @Test
    void realUserWhenAuthenticated() throws Exception {
        CurrentUser user = new CurrentUser(java.util.UUID.randomUUID(), "alice", User.ROLE_USER);
        Authentication auth = new UsernamePasswordAuthenticationToken(user, null, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            Object resolved = resolver.resolveArgument(null, null, null, null);
            assertSame(user, resolved);
            assertFalse(((CurrentUser) resolved).isGuest());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
