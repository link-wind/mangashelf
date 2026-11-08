package com.mangashelf.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Bearer 认证过滤器（DESIGN 17.1）：命中 Bearer 头时认证并注入 TokenAuthentication，
 * 不写入会话（每请求独立）；无效 Token 直接 401（不区分撤销与过期，与 17.1 一致）。
 * 无 Bearer 头的请求（浏览器 Session）原样放行。
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    private final TokenService tokens;

    public BearerTokenFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        String raw = header.substring("Bearer ".length()).trim();
        TokenAuthentication auth = raw.startsWith(TokenService.TOKEN_PREFIX)
                ? tokens.authenticate(raw) : null;
        if (auth == null) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"INVALID_TOKEN\",\"message\":\"接入凭证不合法或已失效。\"}");
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            // Bearer 上下文只在请求生命周期内存在；不持久化到会话
            SecurityContextHolder.clearContext();
        }
    }

    /** CSRF 豁免判定：携带 Bearer 头的请求（Token 客户端无 Cookie，CSRF 威胁模型不适用）。 */
    public static boolean isBearerRequest(jakarta.servlet.http.HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith("Bearer ");
    }
}
