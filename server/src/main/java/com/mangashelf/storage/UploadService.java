package com.mangashelf.storage;

import com.mangashelf.config.ApiException;
import com.mangashelf.config.MangaShelfProperties;
import com.mangashelf.ingestion.Job;
import com.mangashelf.ingestion.JobMessage;
import com.mangashelf.ingestion.JobRepository;
import com.mangashelf.ingestion.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 上传协议（DESIGN 10.2）：创建会话与预签名、幂等完成、核验任务编排。
 * 文件验证通过前资产不可绑定；大文件核验走任务，不阻塞 HTTP 请求。
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private final UploadSessionRepository sessions;
    private final AssetRepository assets;
    private final PresignService presign;
    private final JobRepository jobs;
    private final OutboxRepository outbox;
    private final S3Client s3;
    private final MangaShelfProperties props;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public UploadService(UploadSessionRepository sessions, AssetRepository assets,
                         PresignService presign, JobRepository jobs, OutboxRepository outbox,
                         S3Client s3, MangaShelfProperties props, ObjectMapper objectMapper,
                         TransactionTemplate tx) {
        this.sessions = sessions;
        this.assets = assets;
        this.presign = presign;
        this.jobs = jobs;
        this.outbox = outbox;
        this.s3 = s3;
        this.props = props;
        this.objectMapper = objectMapper;
        this.tx = tx;
    }

    public record CreateRequest(String purpose, Long expectedSizeBytes, String expectedSha256,
                                String contentType, String idempotencyKey) {
    }

    public record CreatedUpload(UUID uploadId, UUID assetId, String objectKey,
                                PresignService.PresignedUrl presigned) {
    }

    public CreatedUpload create(UUID ownerId, CreateRequest request) {
        String purpose = request.purpose();
        String contentType = request.contentType();
        String requestHash = requestHash(ownerId, purpose, request.expectedSizeBytes(),
                request.expectedSha256(), contentType, request.idempotencyKey());

        UploadSession existing = sessions
                .findByIdempotencyKey(ownerId, purpose, request.idempotencyKey()).orElse(null);
        if (existing != null) {
            if (!existing.requestHash().equals(requestHash)) {
                throw new ApiException(409, "IDEMPOTENCY_KEY_CONFLICT",
                        "幂等键已被不同请求使用。", java.util.Map.of("upload_session_id", existing.id().toString()));
            }
            // 同键同请求返回同一资源；签名地址按需重发，不重复创建内容（DESIGN 16.2）
            return new CreatedUpload(existing.id(), existing.assetId(), existing.objectKey(),
                    presign.presignPut(existing.objectKey(), contentType));
        }

        UUID uploadId = UUID.randomUUID();
        String objectKey = StorageKeys.uploadOriginal(ownerId, uploadId);
        UUID assetId = assets.insert(ownerId, objectKey, Asset.KIND_ORIGINAL, contentType,
                request.expectedSizeBytes(), request.expectedSha256());
        UploadSession session = new UploadSession(uploadId, ownerId, assetId, purpose, objectKey,
                request.expectedSizeBytes(), request.expectedSha256(), UploadSession.STATUS_PENDING,
                request.idempotencyKey(), requestHash, java.time.Instant.now()
                        .plus(UploadSessionRepository.SESSION_TTL),
                null, null);
        sessions.insert(session);
        log.info("upload session {} created for owner {}", uploadId, ownerId);
        return new CreatedUpload(uploadId, assetId, objectKey, presign.presignPut(objectKey, contentType));
    }

    public record CompleteResult(UUID uploadId, String status, UUID jobId) {
    }

    /**
     * 完成上传：核对对象后创建核验任务；无可信校验信息时由 Worker 流式计算 SHA-256
     * （DESIGN 10.2 步骤 5），返回 202 与任务 ID。
     */
    public CompleteResult complete(UUID uploadId) {
        UploadSession session = sessions.findById(uploadId).orElseThrow(() ->
                new ApiException(404, "UPLOAD_NOT_FOUND", "上传会话不存在。"));
        if (UploadSession.STATUS_VERIFIED.equals(session.status())) {
            // 幂等：已完成的上传返回同一核验任务
            UUID existing = jobs.findByUploadSession(uploadId).stream()
                    .filter(j -> Job.KIND_VERIFY.equals(j.kind()))
                    .map(Job::id).findFirst().orElse(null);
            return new CompleteResult(uploadId, session.status(), existing);
        }
        if (UploadSession.STATUS_FAILED.equals(session.status())) {
            throw new ApiException(409, "UPLOAD_FAILED", "上传会话已失败，请重新上传。");
        }

        long actualSize;
        try {
            actualSize = s3.headObject(HeadObjectRequest.builder()
                    .bucket(props.storage().bucket())
                    .key(session.objectKey())
                    .build()).contentLength();
        } catch (NoSuchKeyException e) {
            throw new ApiException(422, "OBJECT_NOT_FOUND", "对象存储中没有找到上传的文件。");
        }
        if (session.expectedSize() != null && actualSize != session.expectedSize()) {
            sessions.markStatus(uploadId, UploadSession.STATUS_FAILED);
            throw new ApiException(422, "SIZE_MISMATCH", "对象大小与声明大小不一致。",
                    java.util.Map.of("expected", session.expectedSize(), "actual", actualSize));
        }

        UUID jobId = tx.execute(status -> {
            UUID id = jobs.insertOrExisting(Job.KIND_VERIFY, session.ownerId(), uploadId,
                    "verify:" + uploadId);
            outbox.insertFirst(id, payloadOf(id));
            return id;
        });
        return new CompleteResult(uploadId, session.status(), jobId);
    }

    public record UploadSnapshot(UUID uploadId, String status, UUID assetId, String assetState,
                                 java.util.List<JobView> jobs, java.util.List<AssetView> assets) {

        public record JobView(UUID id, String kind, String status, String stage, String errorCode) {
        }

        public record AssetView(UUID id, String kind, String objectKey, String state, Long sizeBytes) {
        }
    }

    public UploadSnapshot snapshot(UUID uploadId) {
        UploadSession session = sessions.findById(uploadId).orElseThrow(() ->
                new ApiException(404, "UPLOAD_NOT_FOUND", "上传会话不存在。"));
        String assetState = session.assetId() == null ? null : assets.findById(session.assetId())
                .map(Asset::state).orElse(null);
        java.util.List<UploadSnapshot.JobView> jobViews = jobs.findByUploadSession(uploadId).stream()
                .map(j -> new UploadSnapshot.JobView(j.id(), j.kind(), j.status(), j.stage(), j.errorCode()))
                .toList();
        java.util.List<UploadSnapshot.AssetView> assetViews = assets.findByUploadSession(uploadId).stream()
                .map(a -> new UploadSnapshot.AssetView(a.id(), a.kind(), a.objectKey(), a.state(), a.sizeBytes()))
                .toList();
        return new UploadSnapshot(uploadId, session.status(), session.assetId(), assetState,
                jobViews, assetViews);
    }

    public PresignService.PresignedUrl assetAccess(UUID ownerId, UUID assetId) {
        Asset asset = assets.findById(assetId).orElseThrow(() ->
                new ApiException(404, "ASSET_NOT_FOUND", "资产不存在。"));
        // M0 单演示 owner；M1 起按所有权与可见性统一判断（DESIGN 3.3）
        if (!asset.ownerId().equals(ownerId)) {
            throw new ApiException(403, "FORBIDDEN", "无权访问该资产。");
        }
        return presign.presignGet(asset.objectKey());
    }

    private String requestHash(Object... fields) {
        String joined = java.util.Arrays.stream(fields)
                .map(f -> f == null ? "" : String.valueOf(f))
                .reduce((a, b) -> a + "\u001f" + b)
                .orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String payloadOf(UUID jobId) {
        try {
            return objectMapper.writeValueAsString(new JobMessage(JobMessage.SCHEMA_VERSION, jobId, 1));
        } catch (Exception e) {
            throw new IllegalStateException("payload serialization failed", e);
        }
    }
}
