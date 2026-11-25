package com.mangashelf.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.util.UUID;

/**
 * 浏览器会话安全（DESIGN 16）：同源 Cookie Session（Spring Session JDBC）+ SPA CSRF。
 * 仅 API 角色装配（Worker 无 Web 容器）。
 */
@Configuration
@EnableWebSecurity
@Profile("api")
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, TokenService tokenService) throws Exception {
        http
                .csrf(csrf -> csrf
                        // Cookie 仓库 + 纯 AttributeHandler：Cookie 存原始令牌，SPA 回传 X-XSRF-TOKEN 头。
                        // （XOR 委派模式面向令牌渲染进页面的场景，与 Cookie 仓库往返不一致，实测 403）
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // Bearer 客户端不携带 Cookie，CSRF 威胁模型不适用（DESIGN 16/17）
                        .ignoringRequestMatchers(BearerTokenFilter::isBearerRequest))
                .addFilterBefore(new BearerTokenFilter(tokenService),
                        org.springframework.security.web.csrf.CsrfFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), CsrfCookieFilter.afterWhichFilter())
                .authorizeHttpRequests(auth -> auth
                        // 公开端点：健康检查与登录/注册
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus",
                                "/actuator/health/**", "/api/v1/auth/register", "/api/v1/auth/login")
                        .permitAll()
                        // 访客浏览（DESIGN 3.1/3.2）：公开内容的只读端点无需登录。
                        // 数据层保证访客（viewerId=全零）只见 PUBLIC+PUBLISHED 内容。
                        // 写操作、/me/**、/uploads/**、/integrations/**、/admin/**、/jobs/** 仍要求登录。
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/manga", "/api/v1/manga/**",
                                "/api/v1/works", "/api/v1/works/*",
                                "/api/v1/chapters/*", "/api/v1/chapter-revisions/*",
                                "/api/v1/chapter-revisions/*/pages",
                                "/api/v1/chapter-revisions/*/animations",
                                "/api/v1/animations", "/api/v1/animations/*",
                                "/api/v1/animations/*/playback",
                                "/api/v1/manga-catalog", "/api/v1/manga-catalog/**",
                                "/api/v1/manga-discovery",
                                "/api/v1/manga-sources", "/api/v1/manga-sources/**",
                                "/api/v1/catalog-items", "/api/v1/catalog-items/**",
                                "/api/v1/catalog-covers/**",
                                "/api/v1/bangumi-covers/**",
                                "/api/v1/calendar",
                                "/api/v1/danmaku",
                                "/api/v1/external-episodes/*/resolve",
                                "/api/v1/external-sources")
                        .permitAll()
                        // 站内检索：POST 但只读语义，访客只召回公开内容（DESIGN 3.3）
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/v1/search", "/api/v1/search/documents")
                        .permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // 未登录访问受保护资源：返回 401 JSON（而非默认 403 空响应），
                // 前端据此引导登录；已登录但无权访问：403 JSON（真实错误，不重定向）。
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, ex) ->
                                writeError(res, 401, "UNAUTHENTICATED", "需要登录。"))
                        .accessDeniedHandler((req, res, ex) ->
                                writeError(res, 403, "FORBIDDEN", "无权访问该资源。")))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());
        return http.build();
    }

    /** 以统一错误结构写出 401/403 JSON（DESIGN 16.2），供认证入口与授权拒绝处理器复用。 */
    private static void writeError(jakarta.servlet.http.HttpServletResponse res, int status,
                                   String code, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"requestId\":\"" + UUID.randomUUID() + "\",\"details\":{}}");
    }
}
