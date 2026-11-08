package com.mangashelf.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求可观测性（DESIGN 20.2）：request_id 贯穿日志与响应头，认证后补 user_id。
 * 日志 pattern 经 %X{request_id} %X{user_id} 关联（application.yml）。
 */
@Component
public class RequestObservabilityFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = "req_" + UUID.randomUUID();
        MDC.put("request_id", requestId);
        response.setHeader("X-Request-Id", requestId);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.mangashelf.identity.CurrentUser user) {
            MDC.put("user_id", user.id().toString());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("request_id");
            MDC.remove("user_id");
        }
    }
}
