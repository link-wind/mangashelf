package com.mangashelf.ingestion;

import com.mangashelf.config.MangaShelfProperties;
import com.mangashelf.storage.Asset;
import com.mangashelf.storage.AssetRepository;
import com.mangashelf.storage.UploadSession;
import com.mangashelf.storage.UploadSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * content.verify：流式计算对象 SHA-256（S3 ETag 不当作通用摘要，DESIGN 10.2）。
 * 通过后资产与会话在同一事务内置 VERIFIED；不派生后续任务。
 */
@Component
public class VerifyJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(VerifyJobHandler.class);
    private static final int BUFFER_SIZE = 8192;

    private final S3Client s3;
    private final MangaShelfProperties props;
    private final AssetRepository assets;
    private final UploadSessionRepository sessions;
    private final TransactionTemplate tx;

    public VerifyJobHandler(S3Client s3, MangaShelfProperties props,
                            AssetRepository assets, UploadSessionRepository sessions,
                            TransactionTemplate tx) {
        this.s3 = s3;
        this.props = props;
        this.assets = assets;
        this.sessions = sessions;
        this.tx = tx;
    }

    @Override
    public String kind() {
        return Job.KIND_VERIFY;
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        Job job = ctx.job();
        UploadSession session = sessions.findById(job.uploadSessionId()).orElse(null);
        if (session == null) {
            return JobResult.failure("SESSION_NOT_FOUND", "上传会话不存在");
        }
        Asset asset = session.assetId() == null ? null : assets.findById(session.assetId()).orElse(null);
        if (asset == null) {
            return JobResult.failure("ASSET_NOT_FOUND", "上传缺少关联资产");
        }

        ctx.progress().emit("verify", "流式读取对象并计算摘要");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                .bucket(props.storage().bucket()).key(session.objectKey()).build())) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
                size += n;
            }
        }
        String sha256 = HexFormat.of().formatHex(digest.digest());
        log.info("job {} verified object {} size={} sha256={}", job.id(), session.objectKey(), size, sha256);

        if (session.expectedSha256() != null && !session.expectedSha256().equalsIgnoreCase(sha256)) {
            ctx.progress().emit("verify", "声明摘要与实际内容不一致");
            return JobResult.failure("HASH_MISMATCH", "声明摘要与实际内容不一致，请重新上传。");
        }

        ctx.progress().emit("verify", "摘要核验完成，提交资产状态");
        final long verifiedSize = size;
        final String verifiedSha256 = sha256;
        tx.executeWithoutResult(status -> {
            assets.markVerified(asset.id(), verifiedSize, verifiedSha256);
            sessions.markStatus(session.id(), UploadSession.STATUS_VERIFIED);
        });
        // M1 起 verify 不再链式派生：解析由所有者显式创建章节版本触发（implement-manga-reading D3）
        return JobResult.success("verified");
    }
}
