package com.mangashelf.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 确保每个请求都会生成 CSRF 令牌并写入 XSRF-TOKEN Cookie，供 SPA 首次登录前读取。 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    static Class<? extends jakarta.servlet.Filter> afterWhichFilter() {
        return CsrfFilter.class;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            // 强制生成令牌值，使 CookieCsrfTokenRepository 写出 Cookie
            token.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
