package com.mangashelf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 一个 Java 工程、两种运行角色（DESIGN 6.1）：
 * <ul>
 *   <li>api：HTTP、目录、上传、派发；默认角色</li>
 *   <li>worker：队列消费、任务执行（无 Web 容器、不执行迁移）</li>
 * </ul>
 * 角色由 {@code --mangashelf.role=api|worker} 或环境变量 {@code MANGASHELF_ROLE} 决定，
 * 映射为同名 Spring profile。
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class MangaShelfApplication {

    public static void main(String[] args) {
        String role = resolveRole(args);
        SpringApplication app = new SpringApplication(MangaShelfApplication.class);
        app.setAdditionalProfiles(role);
        if ("worker".equals(role)) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        app.run(args);
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
