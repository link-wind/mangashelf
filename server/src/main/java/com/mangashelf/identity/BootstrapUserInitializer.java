package com.mangashelf.identity;

import com.mangashelf.config.MangaShelfProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

/**
 * 引导管理员（M1 开发便利，validate-core-stack design.md D2）：
 * V2 种子行的密码哈希为 PENDING_BOOTSTRAP，首次启动时以配置密码落 bcrypt；
 * 已初始化则跳过。真实用户治理后置。
 */
@Configuration
@Profile("api")
public class BootstrapUserInitializer {

    private static final Logger log = LoggerFactory.getLogger(BootstrapUserInitializer.class);
    private static final String PENDING = "PENDING_BOOTSTRAP";
    private static final String DEV_DEFAULT = "mangashelf-admin";
    private static final String EXAMPLE_DEFAULT = "change-me-before-first-boot";

    @Bean
    public ApplicationRunner bootstrapAdmin(UserRepository users, PasswordEncoder encoder,
                                            MangaShelfProperties props) {
        return args -> {
            UUID adminId = props.demoOwnerId();
            users.findById(adminId).ifPresent(admin -> {
                if (PENDING.equals(admin.passwordHash())) {
                    String password = props.adminPassword();
                    if (password == null || password.isBlank()) {
                        throw new IllegalStateException(
                                "MANGASHELF_ADMIN_PASSWORD must be set before first startup");
                    }
                    if (DEV_DEFAULT.equals(password) || EXAMPLE_DEFAULT.equals(password)) {
                        log.warn("bootstrap admin '{}' uses the development default password; "
                                + "set MANGASHELF_ADMIN_PASSWORD before exposing this instance",
                                admin.login());
                    }
                    users.updatePasswordHash(adminId, encoder.encode(password));
                    log.info("bootstrap admin '{}' initialized", admin.login());
                }
            });
        };
    }
}
