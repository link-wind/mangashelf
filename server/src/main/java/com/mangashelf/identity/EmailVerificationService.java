package com.mangashelf.identity;

import com.mangashelf.config.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 注册邮箱 6 位验证码编排服务：
 * - 60 秒单邮箱发送冷却 + 单邮箱日上限 + 单 IP 小时上限；
 * - 验证码仅以 SHA-256 哈希入库，常数时间比对，单次消费，连续错 5 次自动锁定；
 * - 发信与落库同事务：SMTP 失败自动回滚，不白占冷却名额。
 */
@Service
public class EmailVerificationService {

    private static final int MAX_ATTEMPTS = 5;
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");
    private static final Pattern SIX_DIGITS = Pattern.compile("^[0-9]{6}$");

    private final MailProperties props;
    private final EmailVerificationRepository codes;
    private final UserRepository users;
    private final EmailSenderService sender;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public record SendResult(boolean sent, int cooldownSeconds, int expiresInMinutes) {}

    @Autowired
    public EmailVerificationService(MailProperties props,
                                    EmailVerificationRepository codes,
                                    UserRepository users,
                                    EmailSenderService sender,
                                    TransactionTemplate tx) {
        this(props, codes, users, sender, tx, Clock.systemUTC());
    }

    EmailVerificationService(MailProperties props,
                             EmailVerificationRepository codes,
                             UserRepository users,
                             EmailSenderService sender,
                             TransactionTemplate tx,
                             Clock clock) {
        this.props = props;
        this.codes = codes;
        this.users = users;
        this.sender = sender;
        this.tx = tx;
        this.clock = clock;
    }

    public boolean isRequired() {
        return props.enabled();
    }

    public int resendCooldownSeconds() {
        return props.resendCooldownSeconds();
    }

    /** 校验并发送 6 位注册邮箱验证码。 */
    public SendResult sendRegisterCode(String rawEmail, String clientIp) {
        if (!props.enabled()) {
            throw new ApiException(400, "MAIL_DISABLED", "站点未开启邮箱验证功能。");
        }
        String email = normalizeAndValidateEmail(rawEmail);
        if (users.existsByEmail(email)) {
            throw new ApiException(409, "EMAIL_TAKEN", "该邮箱已注册账号，请直接登录或更换邮箱。");
        }
        Instant now = clock.instant();
        var latest = codes.findLatestCreatedAt(email, EmailVerificationRepository.PURPOSE_REGISTER);
        if (latest.isPresent()) {
            long elapsed = Duration.between(latest.get(), now).getSeconds();
            if (elapsed < props.resendCooldownSeconds()) {
                long wait = props.resendCooldownSeconds() - elapsed;
                throw new ApiException(429, "RATE_LIMITED",
                        "发送过于频繁，请 " + wait + " 秒后再试。",
                        Map.of("retryAfterSeconds", wait));
            }
        }
        long dailyCount = codes.countSinceByEmail(email, EmailVerificationRepository.PURPOSE_REGISTER,
                now.minus(Duration.ofHours(24)));
        if (dailyCount >= props.maxDailyPerEmail()) {
            throw new ApiException(429, "RATE_LIMITED", "该邮箱今日验证码发送次数已达上限，请明天再试。");
        }
        String safeIp = clientIp == null ? "" : clientIp.trim();
        if (!safeIp.isBlank()) {
            long ipCount = codes.countSinceByIp(safeIp, now.minus(Duration.ofHours(1)));
            if (ipCount >= props.maxHourlyPerIp()) {
                throw new ApiException(429, "RATE_LIMITED", "当前网络请求验证码过于频繁，请稍后再试。");
            }
        }

        String rawCode = generateSixDigitCode();
        String hash = hashCode(email, EmailVerificationRepository.PURPOSE_REGISTER, rawCode);
        Instant expiresAt = now.plus(Duration.ofMinutes(props.codeTtlMinutes()));

        Runnable persistAndSend = () -> {
            codes.invalidateUnconsumed(email, EmailVerificationRepository.PURPOSE_REGISTER, now);
            codes.insert(UUID.randomUUID(), email, EmailVerificationRepository.PURPOSE_REGISTER,
                    hash, safeIp, MAX_ATTEMPTS, expiresAt);
            sender.sendRegisterVerificationCode(email, rawCode, props.codeTtlMinutes());
        };
        if (tx != null) {
            tx.executeWithoutResult(status -> persistAndSend.run());
        } else {
            persistAndSend.run();
        }
        return new SendResult(true, props.resendCooldownSeconds(), props.codeTtlMinutes());
    }

    /** 校验并消费 6 位注册邮箱验证码；校验通过返回规范化小写邮箱。 */
    public String verifyAndConsumeRegisterCode(String rawEmail, String rawCode) {
        String email = normalizeAndValidateEmail(rawEmail);
        String code = rawCode == null ? "" : rawCode.trim();
        if (!SIX_DIGITS.matcher(code).matches()) {
            throw new ApiException(400, "INVALID_CODE", "请输入 6 位数字邮箱验证码。");
        }
        Instant now = clock.instant();
        var activeOpt = codes.findLatestActive(email, EmailVerificationRepository.PURPOSE_REGISTER, now);
        if (activeOpt.isEmpty()) {
            throw new ApiException(400, "CODE_EXPIRED", "验证码不存在或已过期，请重新获取。");
        }
        var record = activeOpt.get();
        if (record.attempts() >= record.maxAttempts()) {
            codes.markConsumed(record.id(), now);
            throw new ApiException(400, "CODE_LOCKED", "验证码错误次数过多已失效，请重新获取。");
        }
        String expectedHash = hashCode(email, EmailVerificationRepository.PURPOSE_REGISTER, code);
        boolean matched = MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.UTF_8),
                record.codeHash().getBytes(StandardCharsets.UTF_8));
        if (!matched) {
            codes.incrementAttempts(record.id());
            if (record.attempts() + 1 >= record.maxAttempts()) {
                codes.markConsumed(record.id(), now);
                throw new ApiException(400, "CODE_LOCKED", "验证码错误次数过多已失效，请重新获取。");
            }
            throw new ApiException(400, "INVALID_CODE", "邮箱验证码不正确，请检查后重试。");
        }
        codes.markConsumed(record.id(), now);
        return email;
    }

    public static String normalizeAndValidateEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "INVALID_EMAIL", "请输入有效的电子邮箱地址。");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 160 || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new ApiException(400, "INVALID_EMAIL", "邮箱格式不正确，请检查后重试。");
        }
        return normalized;
    }

    private String generateSixDigitCode() {
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    static String hashCode(String normalizedEmail, String purpose, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((normalizedEmail + ":" + purpose + ":" + code)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
