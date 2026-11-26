package com.mangashelf.identity;

import com.mangashelf.config.ApiException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 标准 SMTP 发信服务（Resend / QQ / 163 / Gmail / 自建 Postfix 通用）。
 * 凭据仅从服务端环境变量注入，绝不打印密码或验证码明文到日志。
 */
@Service
public class EmailSenderService {

    private static final Logger log = LoggerFactory.getLogger(EmailSenderService.class);

    private final MailProperties props;

    public EmailSenderService(MailProperties props) {
        this.props = props;
    }

    public void sendRegisterVerificationCode(String toEmail, String code, int ttlMinutes) {
        if (!props.enabled()) {
            throw new ApiException(503, "MAIL_DISABLED", "站点尚未启用邮件服务。");
        }
        if (props.host() == null || props.host().isBlank() || props.from() == null || props.from().isBlank()) {
            throw new ApiException(503, "MAIL_NOT_CONFIGURED", "服务端邮件 SMTP 配置不完整，请联系管理员。");
        }
        try {
            JavaMailSenderImpl sender = buildSender();
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(parseFromAddress(props.from()));
            helper.setTo(toEmail);
            helper.setSubject("【GemiNeko 杰米猫】注册邮箱验证码：" + code);
            helper.setText(buildPlainText(code, ttlMinutes), buildHtml(code, ttlMinutes));
            sender.send(message);
            log.info("sent registration verification email to {}", maskEmail(toEmail));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("failed to send registration email to {}: {}", maskEmail(toEmail), e.getMessage());
            throw new ApiException(503, "MAIL_SEND_FAILED", "验证码邮件发送失败，请稍后重试或检查邮箱地址。");
        }
    }

    private JavaMailSenderImpl buildSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(props.host().trim());
        sender.setPort(props.port());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        boolean hasAuth = props.username() != null && !props.username().isBlank();
        if (hasAuth) {
            sender.setUsername(props.username().trim());
            sender.setPassword(props.password() == null ? "" : props.password());
        }
        Properties p = sender.getJavaMailProperties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.auth", String.valueOf(hasAuth));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.writetimeout", "10000");
        if (props.ssl()) {
            p.put("mail.smtp.ssl.enable", "true");
            p.put("mail.smtp.ssl.checkserveridentity", "true");
        } else if (props.starttls()) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
        }
        return sender;
    }

    private static InternetAddress parseFromAddress(String rawFrom) throws Exception {
        String trimmed = rawFrom.trim();
        InternetAddress[] parsed = InternetAddress.parse(trimmed, false);
        if (parsed.length == 0) {
            throw new IllegalArgumentException("发件人地址格式无效");
        }
        return parsed[0];
    }

    private static String buildPlainText(String code, int ttlMinutes) {
        return """
                你好，欢迎注册 GemiNeko（杰米猫）！

                你的注册邮箱验证码是：%s

                验证码将在 %d 分钟后失效，请勿向他人泄露。如果这不是你本人的操作，请直接忽略本邮件。
                """.formatted(code, ttlMinutes);
    }

    private static String buildHtml(String code, int ttlMinutes) {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <body style="margin:0;padding:24px;background:#f4f6f8;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;color:#18191c;">
                  <div style="max-width:480px;margin:0 auto;background:#ffffff;border-radius:16px;padding:32px 28px;border:1px solid #e5e7eb;">
                    <div style="font-size:18px;font-weight:800;letter-spacing:-0.3px;color:#00aeec;margin-bottom:8px;">GemiNeko · 杰米猫</div>
                    <h1 style="margin:0 0 12px;font-size:20px;color:#111827;">验证你的注册邮箱</h1>
                    <p style="margin:0 0 20px;font-size:14px;line-height:1.7;color:#4b5563;">
                      请将下方 6 位数字验证码填入注册页面以完成账号创建：
                    </p>
                    <div style="margin:0 0 20px;padding:18px 16px;background:#f0f9ff;border:1px solid #bae6fd;border-radius:12px;text-align:center;">
                      <span style="font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:32px;font-weight:800;letter-spacing:10px;color:#0369a1;">%s</span>
                    </div>
                    <p style="margin:0;font-size:13px;line-height:1.65;color:#6b7280;">
                      验证码 <strong>%d 分钟</strong>内有效且仅可使用一次，请勿转发给他人。<br/>
                      如非本人操作，直接忽略此邮件即可。
                    </p>
                  </div>
                </body>
                </html>
                """.formatted(code, ttlMinutes);
    }

    static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) return local.charAt(0) + "***" + domain;
        return local.substring(0, 2) + "***" + domain;
    }
}
