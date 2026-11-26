package com.mangashelf.identity;

import com.mangashelf.config.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** 注册 / 邮箱验证 / 登录（用户名或邮箱）/ 登出 / 当前用户（DESIGN 16.1 身份）。仅 API 角色装配。 */
@RestController
@RequestMapping("/api/v1/auth")
@Validated
@Profile("api")
public class AuthController {

    public record AuthConfigResponse(boolean emailVerificationRequired, int resendCooldownSeconds) {}

    public record SendEmailCodeRequest(@NotBlank String email) {}

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{3,32}") String login,
            @NotBlank @Size(min = 8, max = 128) String password,
            String email,
            String verificationCode) {
        public RegisterRequest(String login, String password) {
            this(login, password, null, null);
        }
    }

    public record LoginRequest(@NotBlank String login, @NotBlank String password) {
    }

    public record MeResponse(UUID id, String login, String role, Instant createdAt, String email) {
        public MeResponse(UUID id, String login, String role) {
            this(id, login, role, null, null);
        }

        public MeResponse(UUID id, String login, String role, Instant createdAt) {
            this(id, login, role, createdAt, null);
        }
    }

    private final UserRepository users;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final EmailVerificationService emailVerification;
    private final TransactionTemplate tx;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    @Autowired
    public AuthController(UserRepository users,
                          org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                          AuthenticationManager authenticationManager,
                          EmailVerificationService emailVerification,
                          TransactionTemplate tx) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.emailVerification = emailVerification;
        this.tx = tx;
    }

    public AuthController(UserRepository users,
                          org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                          AuthenticationManager authenticationManager) {
        this(users, passwordEncoder, authenticationManager, null, null);
    }

    /** 公开认证配置：告知前端注册页是否需要填写邮箱与 6 位验证码。 */
    @GetMapping("/config")
    public AuthConfigResponse config() {
        boolean required = emailVerification != null && emailVerification.isRequired();
        int cooldown = emailVerification == null ? 60 : emailVerification.resendCooldownSeconds();
        return new AuthConfigResponse(required, cooldown);
    }

    /** 发送注册用 6 位邮箱验证码。 */
    @PostMapping("/email-code")
    public EmailVerificationService.SendResult sendEmailCode(
            @Validated @RequestBody SendEmailCodeRequest request,
            HttpServletRequest httpRequest) {
        if (emailVerification == null || !emailVerification.isRequired()) {
            throw new ApiException(400, "MAIL_DISABLED", "站点尚未启用邮箱验证功能。");
        }
        return emailVerification.sendRegisterCode(request.email(), extractClientIp(httpRequest));
    }

    @PostMapping("/register")
    public ResponseEntity<MeResponse> register(@Validated @RequestBody RegisterRequest request) {
        if (users.existsByLogin(request.login())) {
            throw new ApiException(409, "LOGIN_TAKEN", "登录名已被使用。");
        }
        boolean requireEmail = emailVerification != null && emailVerification.isRequired();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        Runnable doRegister = () -> {
            String verifiedEmail = null;
            Instant verifiedAt = null;
            if (requireEmail) {
                verifiedEmail = emailVerification.verifyAndConsumeRegisterCode(
                        request.email(), request.verificationCode());
                if (users.existsByEmail(verifiedEmail)) {
                    throw new ApiException(409, "EMAIL_TAKEN", "该邮箱已被注册。");
                }
                verifiedAt = now;
            } else if (request.email() != null && !request.email().isBlank()) {
                verifiedEmail = EmailVerificationService.normalizeAndValidateEmail(request.email());
                if (users.existsByEmail(verifiedEmail)) {
                    throw new ApiException(409, "EMAIL_TAKEN", "该邮箱已被注册。");
                }
            }
            users.insert(new User(id, request.login(),
                    passwordEncoder.encode(request.password()),
                    User.ROLE_USER, User.STATUS_ACTIVE, now, now, verifiedEmail, verifiedAt));
        };

        if (tx != null) {
            tx.executeWithoutResult(status -> doRegister.run());
        } else {
            doRegister.run();
        }
        User created = users.findById(id).orElse(null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MeResponse(id, request.login(), User.ROLE_USER, now,
                        created == null ? null : created.email()));
    }

    @PostMapping("/login")
    public MeResponse login(@Validated @RequestBody LoginRequest request,
                            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String identifier = request.login().trim();
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(identifier, request.password()));
            User user = users.findByLoginOrEmail(identifier).orElseThrow();
            CurrentUser principal = new CurrentUser(user.id(), user.login(), user.role());
            Authentication sessionAuth = new UsernamePasswordAuthenticationToken(
                    principal, null, auth.getAuthorities());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(sessionAuth);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, httpRequest, httpResponse);
            return new MeResponse(principal.id(), principal.login(), principal.role(),
                    user.createdAt(), user.email());
        } catch (AuthenticationException e) {
            // 不区分账户不存在与密码错误（规格：不暴露账户是否存在）
            throw new ApiException(401, "INVALID_CREDENTIALS", "登录名、邮箱或密码不正确。");
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
        User found = users.findById(user.id()).orElse(null);
        return new MeResponse(user.id(), user.login(), user.role(),
                found == null ? null : found.createdAt(),
                found == null ? null : found.email());
    }

    private static String extractClientIp(HttpServletRequest request) {
        if (request == null) return "";
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
    }
}
