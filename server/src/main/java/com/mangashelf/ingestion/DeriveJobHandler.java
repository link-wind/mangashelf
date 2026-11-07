package com.mangashelf.ingestion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mangashelf.config.MangaShelfProperties;
import com.mangashelf.storage.Asset;
import com.mangashelf.storage.AssetRepository;
import com.mangashelf.storage.StorageKeys;
import com.mangashelf.storage.UploadSession;
import com.mangashelf.storage.UploadSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * content.process：调用固定 Python 任务生成阅读图与缩略图（DESIGN 10.3 / 12.2）。
 * 只有 result.json 的完整清单通过路径、类型与摘要校验后才上传产物并入库（DESIGN 10.3）。
 */
@Component
public class DeriveJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(DeriveJobHandler.class);

    /** M0 允许的派生产物类型；派生图统一面向阅读（DESIGN 10.3）。 */
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "webp", "image/webp",
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg");

    private final S3Client s3;
    private final MangaShelfProperties props;
    private final AssetRepository assets;
    private final UploadSessionRepository sessions;
    private final PythonTaskRunner python;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public DeriveJobHandler(S3Client s3, MangaShelfProperties props, AssetRepository assets,
                            UploadSessionRepository sessions, PythonTaskRunner python,
                            ObjectMapper objectMapper, TransactionTemplate tx) {
        this.s3 = s3;
        this.props = props;
        this.assets = assets;
        this.sessions = sessions;
        this.python = python;
        this.objectMapper = objectMapper;
        this.tx = tx;
    }

    @Override
    public String kind() {
        return Job.KIND_PROCESS;
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        Job job = ctx.job();
        UploadSession session = sessions.findById(job.uploadSessionId()).orElse(null);
        if (session == null) {
            return JobResult.failure("SESSION_NOT_FOUND", "上传会话不存在");
        }
        Asset original = session.assetId() == null ? null : assets.findById(session.assetId()).orElse(null);
        if (original == null || !Asset.STATE_VERIFIED.equals(original.state())) {
            return JobResult.failure("SOURCE_NOT_VERIFIED", "来源资产尚未通过核验");
        }

        // ── 准备任务目录：输入、输出分离（DESIGN 10.3）──
        Path workDir = ctx.workDir();
        Files.createDirectories(workDir.resolve("input"));
        Files.createDirectories(workDir.resolve("output"));
        Path inputPath = workDir.resolve("input").resolve("source").toAbsolutePath().normalize();
        ctx.progress().emit("prepare", "下载来源对象");
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                .bucket(props.storage().bucket()).key(session.objectKey()).build())) {
            Files.copy(in, inputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        // ── 固定协议运行 Python 任务 ──
        TaskSpec spec = new TaskSpec(TaskSpec.SCHEMA_VERSION, job.id(), ctx.attempt(), "image.derive",
                java.util.List.of(new TaskSpec.TaskInput("source", "input/source")), "output");
        python.run(workDir, spec, line -> ctx.progress().emit(line.stage(), line.message()));

        // ── 校验完整清单后才提交（DESIGN 10.3：只有完整清单才可提交）──
        JsonNode result = objectMapper.readTree(Files.readString(workDir.resolve("result.json")));
        if (!"SUCCEEDED".equals(result.path("status").asText())) {
            return JobResult.failure("TASK_REPORTED_FAILURE", result.path("message").asText("Python 任务报告失败"));
        }
        JsonNode artifacts = result.path("artifacts");
        if (!artifacts.isArray() || artifacts.isEmpty()) {
            return JobResult.failure("EMPTY_MANIFEST", "任务清单没有产物");
        }

        ctx.progress().emit("publish", "校验并上传产物");
        for (JsonNode artifact : artifacts) {
            uploadArtifact(ctx, session, artifact);
        }
        ctx.progress().emit("publish", "产物已入库");
        return JobResult.success("processed");
    }

    private void uploadArtifact(JobContext ctx, UploadSession session, JsonNode artifact) {
        Job job = ctx.job();
        String relativePath = artifact.path("path").asText();
        Path artifactPath = ctxSafeResolve(ctx, relativePath);
        if (artifactPath == null) {
            throw new IllegalArgumentException("产物路径越界：" + relativePath);
        }
        String extension = extensionOf(artifactPath.getFileName().toString());
        String contentType = ALLOWED_TYPES.get(extension);
        if (contentType == null) {
            throw new IllegalArgumentException("产物类型不被允许：" + extension);
        }
        String declaredSha = artifact.path("sha256").asText();
        long declaredBytes = artifact.path("bytes").asLong(-1);
        byte[] content;
        try {
            content = Files.readAllBytes(artifactPath);
        } catch (IOException e) {
            throw new UncheckedIOException("产物读取失败：" + relativePath, e);
        }
        String actualSha = sha256Hex(content);
        if (!actualSha.equalsIgnoreCase(declaredSha) || content.length != declaredBytes) {
            throw new IllegalArgumentException("产物摘要或大小不一致：" + relativePath);
        }

        String objectKey = StorageKeys.jobArtifact(job.id(), ctx.attempt(), artifactPath.getFileName().toString());
        s3.putObject(PutObjectRequest.builder()
                .bucket(props.storage().bucket())
                .key(objectKey)
                .contentType(contentType)
                .build(), RequestBody.fromBytes(content));

        String kind = artifact.path("role").asText("READER_IMAGE");
        UUID assetId = tx.execute(status -> assets.insertVerified(session.ownerId(), objectKey,
                kind, contentType, content.length, actualSha, session.id()));
        log.info("job {} artifact {} uploaded as asset {}", job.id(), objectKey, assetId);
    }

    /** 路径必须在任务目录内（规格 Scenario：产物路径越界）。 */
    private Path ctxSafeResolve(JobContext ctx, String relativePath) {
        Path workDir = ctx.workDir().toAbsolutePath().normalize();
        Path resolved = workDir.resolve(relativePath).normalize();
        return resolved.startsWith(workDir) ? resolved : null;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
