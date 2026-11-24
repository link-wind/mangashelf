package com.mangashelf.ingestion;

import com.mangashelf.animation.AnimationRepository;
import com.mangashelf.storage.Asset;
import com.mangashelf.storage.AssetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

/**
 * 真实 PostgreSQL（pgvector:pg18）+ RabbitMQ（4-management）容器集成测试基类。
 * 复用本地 compose 同款镜像；关闭后台自动调度与自动消费，使故障注入点完全确定性可控。
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "mangashelf.embedding.enabled=false",
        "mangashelf.bangumi.enabled=false",
        "mangashelf.redis.enabled=false",
        "mangashelf.jobs.dispatch-interval-ms=600000",
        "mangashelf.jobs.recovery-interval-ms=600000",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@ActiveProfiles("worker")
abstract class ReliabilityContainersBase {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("mangashelf_test")
            .withUsername("mangashelf")
            .withPassword("mangashelf");

    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"));

    static {
        POSTGRES.start();
        RABBIT.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { POSTGRES.stop(); } catch (Exception ignored) {}
            try { RABBIT.stop(); } catch (Exception ignored) {}
        }));
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.addresses", () -> RABBIT.getHost() + ":" + RABBIT.getAmqpPort());
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected AssetRepository assets;

    @Autowired
    protected AnimationRepository animations;

    protected UUID createTestUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, login, password_hash, role, status)
                VALUES (?, ?, '{noop}test', 'USER', 'ACTIVE')
                """, id, "u-" + id.toString().substring(0, 8));
        return id;
    }

    protected UUID createUploadSession(UUID ownerId) {
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO upload_sessions (id, owner_id, purpose, object_key, status,
                                             idempotency_key, request_hash, expires_at)
                VALUES (?, ?, 'MANGA_PAGE', ?, 'PENDING', ?, 'hash', ?)
                """, sessionId, ownerId, "uploads/" + sessionId + ".png",
                "idem-" + sessionId, java.sql.Timestamp.from(Instant.now().plusSeconds(600)));
        return sessionId;
    }

    protected UUID createAnimationRevisionWithVideo(UUID ownerId) {
        UUID videoAsset = assets.insertVerified(
                ownerId, "uploads/anim-" + UUID.randomUUID() + ".mp4",
                Asset.KIND_ORIGINAL, "video/mp4", 1024L,
                "0".repeat(64), null);
        UUID animId = animations.insertAnimation(ownerId, "测试动画", "故障注入样本");
        UUID revId = animations.insertRevision(animId, 1);
        jdbc.update("UPDATE animation_revisions SET video_asset_id = ? WHERE id = ?", videoAsset, revId);
        return revId;
    }
}
