package com.mangashelf.identity;

import com.mangashelf.config.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** 注册 / 登录 / 登出 / 当前用户（DESIGN 16.1 身份）。仅 API 角色装配。 */
@RestController
@RequestMapping("/api/v1/auth")
@Validated
@Profile("api")
public class AuthController {

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{3,32}") String login,
            @NotBlank @Size(min = 8, max = 128) String password) {
    }

    public record LoginRequest(@NotBlank String login, @NotBlank String password) {
    }

    public record MeResponse(UUID id, String login, String role, Instant createdAt) {
        public MeResponse(UUID id, String login, String role) {
            this(id, login, role, null);
        }
    }

    private final UserRepository users;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(UserRepository users,
                          org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                          AuthenticationManager authenticationManager) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/register")
    public ResponseEntity<MeResponse> register(@Validated @RequestBody RegisterRequest request) {
        if (users.existsByLogin(request.login())) {
            throw new ApiException(409, "LOGIN_TAKEN", "登录名已被使用。");
        }
        UUID id = UUID.randomUUID();
        users.insert(new User(id, request.login(),
                passwordEncoder.encode(request.password()),
                User.ROLE_USER, User.STATUS_ACTIVE, null, null));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MeResponse(id, request.login(), User.ROLE_USER, Instant.now()));
    }

    @PostMapping("/login")
    public MeResponse login(@Validated @RequestBody LoginRequest request,
                            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.login(), request.password()));
            User user = users.findByLogin(request.login()).orElseThrow();
            CurrentUser principal = new CurrentUser(user.id(), user.login(), user.role());
            Authentication sessionAuth = new UsernamePasswordAuthenticationToken(
                    principal, null, auth.getAuthorities());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(sessionAuth);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, httpRequest, httpResponse);
            return new MeResponse(principal.id(), principal.login(), principal.role(), user.createdAt());
        } catch (AuthenticationException e) {
            // 不区分账户不存在与密码错误（规格：不暴露账户是否存在）
            throw new ApiException(401, "INVALID_CREDENTIALS", "登录名或密码不正确。");
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            new SecurityContextLogoutHandler().logout(request, response, auth);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me(@AuthUser CurrentUser user) {
        Instant createdAt = users.findById(user.id()).map(User::createdAt).orElse(null);
        return new MeResponse(user.id(), user.login(), user.role(), createdAt);
    }
}
