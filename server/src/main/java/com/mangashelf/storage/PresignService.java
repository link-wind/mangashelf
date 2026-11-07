package com.mangashelf.storage;

import com.mangashelf.config.MangaShelfProperties;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 短期签名授权（DESIGN 10.2 / 18.1）；签名地址不写入应用日志。 */
@Service
public class PresignService {

    public record PresignedUrl(String url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    private final S3Presigner presigner;
    private final MangaShelfProperties props;

    public PresignService(S3Presigner presigner, MangaShelfProperties props) {
        this.presigner = presigner;
        this.props = props;
    }

    public PresignedUrl presignPut(String objectKey, String contentType) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(props.storage().bucket())
                .key(objectKey)
                .contentType(contentType)
                .build();
        var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(props.storage().presignTtlSeconds()))
                .putObjectRequest(put)
                .build());
        return toResult(presigned.url().toString(), "PUT", flatten(presigned.signedHeaders()));
    }

    public PresignedUrl presignGet(String objectKey) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(props.storage().bucket())
                .key(objectKey)
                .build();
        var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(props.storage().presignTtlSeconds()))
                .getObjectRequest(get)
                .build());
        return toResult(presigned.url().toString(), "GET", flatten(presigned.signedHeaders()));
    }

    private PresignedUrl toResult(String url, String method, Map<String, String> signedHeaders) {
        return new PresignedUrl(url, method, Map.copyOf(signedHeaders),
                Instant.now().plusSeconds(props.storage().presignTtlSeconds()));
    }

    /** SDK 返回 Map&lt;String, List&lt;String&gt;&gt;；展平为单值表便于客户端携带。 */
    private Map<String, String> flatten(Map<String, List<String>> signedHeaders) {
        return signedHeaders.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, e -> String.join(",", e.getValue())));
    }
}
