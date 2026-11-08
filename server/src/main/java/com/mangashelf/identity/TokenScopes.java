package com.mangashelf.identity;

import com.mangashelf.config.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * scope 显式校验（implement-mangaweaver-integration D3）：
 * 浏览器 Session 认证直接通过；Bearer Token 缺 scope 抛 403（DESIGN 17.1）。
 */
public final class TokenScopes {

    private TokenScopes() {
    }

    public static void require(String scope) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof TokenAuthentication token)) {
            return; // Session 用户：能力由自身权限与资源归属判定
        }
        boolean granted = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("SCOPE_" + scope));
        if (!granted) {
            throw new ApiException(403, "TOKEN_SCOPE_INSUFFICIENT",
                    "接入 Token 缺少所需 scope：" + scope);
        }
    }
}
