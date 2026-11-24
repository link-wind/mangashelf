package com.mangashelf.animation;

import com.mangashelf.config.MangaShelfProperties;
import com.mangashelf.ingestion.Job;
import com.mangashelf.ingestion.JobContext;
import com.mangashelf.ingestion.JobHandler;
import com.mangashelf.ingestion.JobRepository;
import com.mangashelf.ingestion.JobResult;
import com.mangashelf.ingestion.PythonTaskRunner;
import com.mangashelf.ingestion.TaskSpec;
import com.mangashelf.storage.Asset;
import com.mangashelf.storage.AssetRepository;
import com.mangashelf.storage.StorageKeys;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * animation.verify：ffprobe 实测媒体（DESIGN 13.3），声明的 content-type 不替代检查。
 * 通过后在短事务内校验任务租约令牌有效，再原子落库媒体元数据与 READY 状态，
 * 避免过期 Worker 覆盖新 attempt 的有效结果（harden-reliable-jobs-and-fault-injection）。
 */
@Component
public class AnimationVerifyHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(AnimationVerifyHandler.class);
    private static final Map<String, String> POSTER_TYPES = Map.of(
            "jpg", "image/jpeg", "jpeg", "image/jpeg");
    private static final Map<String, String> SUBTITLE_TYPES = Map.of(
            "vtt", "text/vtt");

    private final AnimationRepository animations;
    private final AssetRepository assets;
    private final JobRepository jobs;
    private final S3Client s3;
    private final MangaShelfProperties props;
    private final PythonTaskRunner python;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public AnimationVerifyHandler(AnimationRepository animations, AssetRepository assets,
                                  JobRepository jobs, S3Client s3, MangaShelfProperties props,
                                  PythonTaskRunner python, ObjectMapper objectMapper,
                                  TransactionTemplate tx) {
        this.animations = animations;
        this.assets = assets;
        this.jobs = jobs;
        this.s3 = s3;
        this.props = props;
        this.python = python;
        this.objectMapper = objectMapper;
        this.tx = tx;
    }

    @Override
    public String kind() {
        return Job.KIND_ANIMATION_VERIFY;
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        Job job = ctx.job();
        AnimationRevision revision = animations.findRevision(job.animationRevisionId()).orElse(null);
        if (revision == null) {
            return JobResult.failure("REVISION_NOT_FOUND", "动画版本不存在");
        }
        if (revision.videoAssetId() == null) {
            return JobResult.failure("VIDEO_NOT_BOUND", "版本尚未绑定视频资产");
        }
        animations.markRevisionStatus(revision.id(), AnimationRevision.STATUS_VERIFYING, null, null);

        if (ctx.cancelled()) {
            return new JobResult(Job.STATUS_CANCELLED, "cancelled", null, "CANCELLED", "任务已被所有者取消");
        }

        // ── 下载输入：视频必选，封面/字幕可选 ──
        Path workDir = ctx.workDir();
        Files.createDirectories(workDir.resolve("input"));
        Files.createDirectories(workDir.resolve("output"));
        List<TaskSpec.TaskInput> inputs = new ArrayList<>();
        downloadTo(workDir, "video", revision.videoAssetId(), inputs);
        if (revision.posterAssetId() != null) {
            downloadTo(workDir, "poster", revision.posterAssetId(), inputs);
        }
        if (revision.subtitleAssetId() != null) {
            downloadTo(workDir, "subtitle", revision.subtitleAssetId(), inputs);
        }

        if (ctx.cancelled()) {
            return new JobResult(Job.STATUS_CANCELLED, "cancelled", null, "CANCELLED", "任务已被所有者取消");
        }

        // ── 固定协议运行 ffprobe/ffmpeg 任务 ──
        TaskSpec spec = new TaskSpec(TaskSpec.SCHEMA_VERSION, job.id(), ctx.attempt(), "media.verify",
                inputs, "output", null);
        python.run(workDir, spec, line -> ctx.progress().emit(line.stage(), line.message()));

        JsonNode result = objectMapper.readTree(Files.readString(workDir.resolve("result.json")));
        if (!"SUCCEEDED".equals(result.path("status").asText())) {
            String code = result.path("error_code").asText("MEDIA_INVALID");
            String message = result.path("message").asText("媒体校验失败");
            if (!markFailedIfLeased(job.id(), ctx.leaseToken(), revision.id(), code, message)) {
                return JobResult.failure("STALE_LEASE", "租约已失效，放弃覆盖动画版本状态");
            }
            return JobResult.failure(code, message);
        }

        // ── 媒体元数据校验 ──
        JsonNode media = result.path("media");
        if (!media.isObject()) {
            if (!markFailedIfLeased(job.id(), ctx.leaseToken(), revision.id(),
                    "MEDIA_RESULT_INVALID", "任务结果缺少媒体信息")) {
                return JobResult.failure("STALE_LEASE", "租约已失效，放弃覆盖动画版本状态");
            }
            return JobResult.failure("MEDIA_RESULT_INVALID", "任务结果缺少媒体信息");
        }

        // ── 产物上传在短事务外完成；落库前校验租约仍有效 ──
        ctx.progress().emit("publish", "上传封面与字幕产物");
        UUID posterAsset = null;
        UUID subtitleAsset = null;
        for (JsonNode artifact : result.path("artifacts")) {
            String role = artifact.path("role").asText();
            UUID assetId = uploadArtifact(ctx, revision.id(), artifact, role);
            if ("POSTER".equals(role)) posterAsset = assetId;
            if ("SUBTITLE".equals(role)) subtitleAsset = assetId;
        }

        boolean committed = commitVerifiedIfLeased(
                job.id(), ctx.leaseToken(), revision, posterAsset, subtitleAsset, media);
        if (!committed) {
            log.warn("job {} stale lease before animation revision {} commit, discarding",
                    job.id(), revision.id());
            return JobResult.failure("STALE_LEASE", "租约已失效，拒绝写入动画版本元数据");
        }

        ctx.progress().emit("publish", "媒体校验完成");
        log.info("job {} media verified for revision {}: {}ms {}x{} audio={}",
                job.id(), revision.id(), media.path("duration_ms").asLong(),
                media.path("width").asInt(), media.path("height").asInt(),
                media.path("has_audio").asBoolean());
        return JobResult.success("verified");
    }

    /**
     * 短事务内租约 Fencing + 媒体元数据绑定 + 状态置 READY（可由集成测试直接构造竞态验证）。
     */
    public boolean commitVerifiedIfLeased(UUID jobId, UUID leaseToken, AnimationRevision revision,
                                          UUID posterAssetId, UUID subtitleAssetId, JsonNode media) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (!jobs.isLeaseValid(jobId, leaseToken)) {
                return false;
            }
            animations.bindAssetsAndMedia(
                    revision.id(), revision.videoAssetId(), posterAssetId, subtitleAssetId,
                    media.path("duration_ms").asLong(),
                    media.path("width").asInt(), media.path("height").asInt(),
                    media.path("video_codec").asText(null), media.path("audio_codec").asText(null),
                    media.path("has_audio").asBoolean(false),
                    revision.sourceManifestSha256());
            animations.markRevisionStatus(revision.id(), AnimationRevision.STATUS_READY, null, null);
            return true;
        }));
    }

    private boolean markFailedIfLeased(UUID jobId, UUID leaseToken, UUID revisionId,
                                       String code, String message) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (!jobs.isLeaseValid(jobId, leaseToken)) {
                return false;
            }
            animations.markRevisionStatus(revisionId, AnimationRevision.STATUS_FAILED, code, message);
            return true;
        }));
    }

    private void downloadTo(Path workDir, String role, UUID assetId, List<TaskSpec.TaskInput> inputs) {
        String key = assets.findObjectKey(assetId).orElseThrow();
        Path target = workDir.resolve("input").resolve(role).toAbsolutePath().normalize();
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                .bucket(props.storage().bucket()).key(key).build())) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("下载输入失败：" + role, e);
        }
        inputs.add(new TaskSpec.TaskInput(role, "input/" + role));
    }

    private UUID uploadArtifact(JobContext ctx, UUID revisionId, JsonNode artifact, String role) {
        Job job = ctx.job();
        String relativePath = artifact.path("path").asText();
        Path workDir = ctx.workDir().toAbsolutePath().normalize();
        Path artifactPath = workDir.resolve(relativePath).normalize();
        if (!artifactPath.startsWith(workDir)) {
            throw new IllegalArgumentException("产物路径越界：" + relativePath);
        }
        String extension = artifactPath.getFileName().toString()
                .substring(artifactPath.getFileName().toString().lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
        String contentType = switch (role) {
            case "POSTER" -> POSTER_TYPES.get(extension);
            case "SUBTITLE" -> SUBTITLE_TYPES.get(extension);
            default -> null;
        };
        if (contentType == null) {
            throw new IllegalArgumentException("产物类型不被允许：" + role + " / " + extension);
        }
        byte[] content;
        try {
            content = Files.readAllBytes(artifactPath);
        } catch (IOException e) {
            throw new UncheckedIOException("产物读取失败：" + relativePath, e);
        }
        String actualSha = sha256Hex(content);
        if (!actualSha.equalsIgnoreCase(artifact.path("sha256").asText())
                || content.length != artifact.path("bytes").asLong(-1)) {
            throw new IllegalArgumentException("产物摘要或大小不一致：" + relativePath);
        }
        String objectKey = StorageKeys.animationArtifact(revisionId,
                "poster.jpg".equals(artifactPath.getFileName().toString()) ? "poster.jpg" : "subtitles.vtt");
        s3.putObject(PutObjectRequest.builder()
                .bucket(props.storage().bucket())
                .key(objectKey)
                .contentType(contentType)
                .build(), RequestBody.fromBytes(content));
        String kind = "POSTER".equals(role) ? Asset.KIND_POSTER : Asset.KIND_SUBTITLE;
        return tx.execute(status -> assets.insertVerified(job.ownerId(), objectKey, kind,
                contentType, content.length, actualSha, null));
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
