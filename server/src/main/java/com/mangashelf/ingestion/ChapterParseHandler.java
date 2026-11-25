package com.mangashelf.ingestion;

import com.mangashelf.catalog.CatalogRepository;
import com.mangashelf.catalog.ChapterRevision;
import com.mangashelf.config.MangaShelfProperties;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * chapter.parse：从已核验上传解析章节版本页面清单（DESIGN 10.3）。
 * Python 产出自然页序的阅读图与缩略图；Java 校验清单后按 18.1 Key 上传，
 * 并在事务内落 pages 与资产，版本置 READY（含 manifest 摘要）。
 */
@Component
public class ChapterParseHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ChapterParseHandler.class);
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "webp", "image/webp",
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg");

    private final CatalogRepository catalog;
    private final AssetRepository assets;
    private final S3Client s3;
    private final MangaShelfProperties props;
    private final PythonTaskRunner python;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public ChapterParseHandler(CatalogRepository catalog, AssetRepository assets, S3Client s3,
                               MangaShelfProperties props, PythonTaskRunner python,
                               ObjectMapper objectMapper, TransactionTemplate tx) {
        this.catalog = catalog;
        this.assets = assets;
        this.s3 = s3;
        this.props = props;
        this.python = python;
        this.objectMapper = objectMapper;
        this.tx = tx;
    }

    @Override
    public String kind() {
        return Job.KIND_CHAPTER_PARSE;
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        Job job = ctx.job();
        ChapterRevision revision = catalog.findRevision(job.chapterRevisionId()).orElse(null);
        if (revision == null) {
            return JobResult.failure("REVISION_NOT_FOUND", "章节版本不存在");
        }

        // 重跑语义（恢复扫描/显式重试）：清理旧页面后重新解析
        catalog.markRevisionStatus(revision.id(), ChapterRevision.STATUS_PROCESSING, null, null);
        if (!catalog.requireLeasedRevision(revision.id(), ctx.leaseToken())) {
            // 租约已过期（被新 attempt 取代或恢复中）：本次执行不产生任何业务写入
            return JobResult.failure("STALE_LEASE", "任务租约已失效，结果被丢弃");
        }

        if (ctx.cancelled()) {
            return new JobResult(Job.STATUS_CANCELLED, "cancelled", null, "CANCELLED", "任务已被所有者取消");
        }

        Path workDir = ctx.workDir();
        Files.createDirectories(workDir.resolve("input"));
        Files.createDirectories(workDir.resolve("output"));

        ctx.progress().emit("prepare", "下载来源对象");
        String sourceKey = assets.findObjectKey(revision.sourceAssetId()).orElse(null);
        if (sourceKey == null) {
            return JobResult.failure("SOURCE_NOT_FOUND", "来源资产不存在");
        }
        Path inputPath = workDir.resolve("input").resolve("source").toAbsolutePath().normalize();
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                .bucket(props.storage().bucket()).key(sourceKey).build())) {
            Files.copy(in, inputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        if (ctx.cancelled()) {
            return new JobResult(Job.STATUS_CANCELLED, "cancelled", null, "CANCELLED", "任务已被所有者取消");
        }

        TaskSpec spec = new TaskSpec(TaskSpec.SCHEMA_VERSION, job.id(), ctx.attempt(), "chapter.parse",
                List.of(new TaskSpec.TaskInput("source", "input/source")), "output",
                new TaskSpec.Limits(props.processing().archiveMaxEntries(),
                        props.processing().archiveMaxTotalBytes(),
                        props.processing().archiveMaxFileBytes()));
        python.run(workDir, spec, line -> ctx.progress().emit(line.stage(), line.message()));

        JsonNode result = objectMapper.readTree(Files.readString(workDir.resolve("result.json")));
        if (!"SUCCEEDED".equals(result.path("status").asText())) {
            String code = result.path("error_code").asText("TASK_REPORTED_FAILURE");
            catalog.markRevisionStatus(revision.id(), ChapterRevision.STATUS_FAILED, code,
                    result.path("message").asText("解析失败"));
            return JobResult.failure(code, result.path("message").asText("Python 任务报告失败"));
        }
        JsonNode pages = result.path("pages");
        if (!pages.isArray() || pages.isEmpty()) {
            catalog.markRevisionStatus(revision.id(), ChapterRevision.STATUS_FAILED, "EMPTY_MANIFEST",
                    "解析结果没有页面");
            return JobResult.failure("EMPTY_MANIFEST", "任务清单没有页面");
        }

        ctx.progress().emit("publish", "校验并上传页面产物");
        // 上传在事务外执行（design 决策 4）：对象独立存在于存储，失败不留下半个 DB 事务。
        StringBuilder manifest = new StringBuilder().append(pages.size()).append('\n');
        List<CatalogRepository.PageRow> pageRows = new java.util.ArrayList<>();
        for (JsonNode page : pages) {
            pageRows.add(uploadPage(ctx, revision.id(), page, manifest));
        }
        // 短事务校验租约后原子切换引用：0 行说明租约已被新 attempt 取代，业务行不落库。
        String manifestSha = sha256Hex(manifest.toString());
        int switched = tx.execute(status -> {
            if (!catalog.requireLeasedRevision(revision.id(), ctx.leaseToken())) return 0;
            catalog.deletePages(revision.id());
            for (CatalogRepository.PageRow row : pageRows) {
                catalog.insertPage(row.id(), revision.id(), row.ordinal(), row.imageAssetId(),
                        row.thumbnailAssetId(), row.width(), row.height());
            }
            catalog.setRevisionPageCount(revision.id(), pages.size(), manifestSha);
            catalog.markRevisionStatus(revision.id(), ChapterRevision.STATUS_READY, null, null);
            return 1;
        });
        if (switched == 0) {
            return JobResult.failure("STALE_LEASE", "任务租约已失效，页面清单未提交");
        }
        ctx.progress().emit("publish", "页面清单已提交");
        log.info("job {} parsed {} pages for revision {}", job.id(), pages.size(), revision.id());
        return JobResult.success("parsed");
    }

    /** 校验并上传页面产物（事务外）；返回待落库的页面行，引用切换在短事务内完成。 */
    private CatalogRepository.PageRow uploadPage(JobContext ctx, UUID revisionId, JsonNode page,
                                                 StringBuilder manifest) {
        int ordinal = page.path("ordinal").asInt(-1);
        if (ordinal <= 0) {
            throw new IllegalArgumentException("页序不合法");
        }
        UUID pageId = UUID.randomUUID();
        UUID imageAsset = uploadArtifact(ctx, revisionId, pageId, ordinal, page.path("reader"));
        UUID thumbAsset = page.hasNonNull("thumbnail")
                ? uploadArtifact(ctx, revisionId, pageId, ordinal, page.path("thumbnail"))
                : null;

        JsonNode reader = page.path("reader");
        manifest.append(ordinal).append(':')
                .append(reader.path("sha256").asText()).append(':')
                .append(page.path("thumbnail").path("sha256").asText()).append('\n');
        return new CatalogRepository.PageRow(pageId, ordinal, imageAsset, thumbAsset,
                reader.path("width").asInt(), reader.path("height").asInt());
    }

    /** 校验（路径越界/类型/摘要/大小）后上传单个产物并登记 VERIFIED 资产。 */
    private UUID uploadArtifact(JobContext ctx, UUID revisionId, UUID pageId, int ordinal,
                                JsonNode artifact) {
        Job job = ctx.job();
        String relativePath = artifact.path("path").asText();
        Path workDir = ctx.workDir().toAbsolutePath().normalize();
        Path artifactPath = workDir.resolve(relativePath).normalize();
        if (!artifactPath.startsWith(workDir)) {
            throw new IllegalArgumentException("产物路径越界：" + relativePath);
        }
        String extension = extensionOf(artifactPath.getFileName().toString());
        String contentType = ALLOWED_TYPES.get(extension);
        if (contentType == null) {
            throw new IllegalArgumentException("产物类型不被允许：" + extension);
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

        String fileName = artifactPath.getFileName().toString();
        String objectKey = StorageKeys.pageArtifact(revisionId, pageId, fileName);
        s3.putObject(PutObjectRequest.builder()
                .bucket(props.storage().bucket())
                .key(objectKey)
                .contentType(contentType)
                .cacheControl(com.mangashelf.storage.PresignService.MediaCacheProfile.IMMUTABLE_IMAGE.headerValue())
                .build(), RequestBody.fromBytes(content));
        String kind = fileName.startsWith("thumbnail") ? "THUMBNAIL" : "READER_IMAGE";
        return tx.execute(status -> assets.insertVerified(job.ownerId(), objectKey, kind,
                contentType, content.length, actualSha, null));
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String sha256Hex(String s) {
        return sha256Hex(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
