package com.mangashelf.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 邮件发信与注册邮箱验证配置（全部从环境变量读取，凭据不入库、不入日志）。
 * enabled=true 时开启注册 6 位邮箱验证码校验；兼容 Resend / QQ / 163 / Gmail 等标准 SMTP。
 */
@ConfigurationProperties(prefix = "mangashelf.mail")
public record MailProperties(
        boolean enabled,
        String host,
        int port,
        boolean ssl,
        boolean starttls,
        String username,
        String password,
        String from,
        int codeTtlMinutes,
        int resendCooldownSeconds,
        int maxDailyPerEmail,
        int maxHourlyPerIp) {

    public MailProperties {
        if (port <= 0) port = 465;
        if (codeTtlMinutes <= 0) codeTtlMinutes = 10;
        if (resendCooldownSeconds <= 0) resendCooldownSeconds = 60;
        if (maxDailyPerEmail <= 0) maxDailyPerEmail = 10;
        if (maxHourlyPerIp <= 0) maxHourlyPerIp = 20;
    }

    public static MailProperties disabled() {
        return new MailProperties(false, "", 465, true, false, "", "", "", 10, 60, 10, 20);
    }
}
