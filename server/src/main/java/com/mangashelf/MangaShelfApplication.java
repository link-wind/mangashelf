package com.mangashelf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 一个 Java 工程、两种运行角色（DESIGN 6.1）：
 * <ul>
 *   <li>api：HTTP、目录、上传、派发；默认角色</li>
 *   <li>worker：队列消费、任务执行（无 Web 容器、不执行迁移）</li>
 * </ul>
 * 角色由 {@code --mangashelf.role=api|worker} 或环境变量 {@code MANGASHELF_ROLE} 决定，
 * 映射为同名 Spring profile。
 * {@code --mangashelf.migrate-only=true}（multi-instance-deployment）：只执行数据库迁移后
 * 优雅退出——多实例部署下迁移由一次性步骤执行，常驻副本禁用启动迁移。
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class MangaShelfApplication {

    public static void main(String[] args) {
        boolean migrateOnly = hasFlag(args, "--mangashelf.migrate-only=true");
        String role = resolveRole(args);
        SpringApplication app = new SpringApplication(MangaShelfApplication.class);
        app.setAdditionalProfiles(role);
        if ("worker".equals(role) || migrateOnly) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        ConfigurableApplicationContext context = app.run(args);
        if (migrateOnly) {
            int code = SpringApplication.exit(context);
            System.exit(code);
        }
    }

    static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg.trim())) return true;
        }
        return "true".equals(System.getenv("MANGASHELF_MIGRATE_ONLY"));
    }

    static String resolveRole(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--mangashelf.role=")) {
                return arg.substring("--mangashelf.role=".length());
            }
        }
        String env = System.getenv("MANGASHELF_ROLE");
        return (env == null || env.isBlank()) ? "api" : env;
    }
}
