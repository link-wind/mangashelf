package com.mangashelf.storage;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AssetRepository {

    private static final String SELECT = """
            SELECT id, owner_id, object_key, kind, state, size_bytes, declared_sha256,
                   verified_sha256, content_type,
                   NULLIF(metadata->>'upload_session_id', '')::uuid AS upload_session_id,
                   created_at, updated_at
            FROM assets
            """;

    private final JdbcTemplate jdbc;
    private final RowMapper<Asset> mapper = new DataClassRowMapper<>(Asset.class);

    public AssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 创建处于 PENDING 的资产；校验摘要由用户声明，不代表已验证事实（DESIGN 10.2）。 */
    public UUID insert(UUID ownerId, String objectKey, String kind, String contentType,
                       Long sizeBytes, String declaredSha256) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assets (id, owner_id, object_key, kind, state, size_bytes, declared_sha256, content_type)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?)
                """, id, ownerId, objectKey, kind, sizeBytes, declaredSha256, contentType);
        return id;
    }

    /** 派生产物资产：由 Worker 完成摘要核验后直接以 VERIFIED 写入（DESIGN 12.2）。 */
    public UUID insertVerified(UUID ownerId, String objectKey, String kind, String contentType,
                               long sizeBytes, String verifiedSha256, UUID uploadSessionId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assets (id, owner_id, object_key, kind, state, size_bytes, verified_sha256,
                                    content_type, metadata)
                VALUES (?, ?, ?, ?, 'VERIFIED', ?, ?, ?, jsonb_build_object('upload_session_id', ?::text))
                """, id, ownerId, objectKey, kind, sizeBytes, verifiedSha256, contentType,
                uploadSessionId.toString());
        return id;
    }

    public Optional<Asset> findById(UUID id) {
        return jdbc.query(SELECT + " WHERE id = ?", mapper, id).stream().findFirst();
    }

    public List<Asset> findByUploadSession(UUID uploadSessionId) {
        return jdbc.query(SELECT + " WHERE metadata->>'upload_session_id' = ? ORDER BY created_at",
                mapper, uploadSessionId.toString());
    }

    public void markVerified(UUID id, long sizeBytes, String verifiedSha256) {
        jdbc.update("""
                UPDATE assets SET state = 'VERIFIED', size_bytes = ?, verified_sha256 = ?, updated_at = now()
                WHERE id = ?
                """, sizeBytes, verifiedSha256, id);
    }

    public void markFailed(UUID id) {
        jdbc.update("UPDATE assets SET state = 'FAILED', updated_at = now() WHERE id = ?", id);
    }
}
