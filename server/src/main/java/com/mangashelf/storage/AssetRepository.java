package com.mangashelf.storage;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
                uploadSessionId == null ? null : uploadSessionId.toString());
        return id;
    }

    public Optional<Asset> findById(UUID id) {
        return jdbc.query(SELECT + " WHERE id = ?", mapper, id).stream().findFirst();
    }

    public Optional<String> findObjectKey(UUID id) {
        return jdbc.query("SELECT object_key FROM assets WHERE id = ?",
                (rs, i) -> rs.getString("object_key"), id).stream().findFirst();
    }

    /** 批量读取资产对象键（消除阅读清单与播放详情的 N+1 SQL）。 */
    public Map<UUID, String> findObjectKeysByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        UUID[] idArray = ids.stream().filter(java.util.Objects::nonNull).distinct().toArray(UUID[]::new);
        if (idArray.length == 0) {
            return Map.of();
        }
        Map<UUID, String> result = new HashMap<>(idArray.length * 2);
        jdbc.query("SELECT id, object_key FROM assets WHERE id = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", idArray)),
                rs -> {
                    result.put(rs.getObject("id", UUID.class), rs.getString("object_key"));
                });
        return result;
    }

    /** 页面资产追溯到内容链（DESIGN 6.1：访问授权属 storage 模块，跨表只读）。 */
    public Optional<PageAssetRef> findPageRefByAsset(UUID assetId) {
        String sql = """
                SELECT p.id AS page_id, r.id AS revision_id, r.status AS revision_status,
                       w.id AS work_id, w.visibility AS work_visibility,
                       w.status AS work_status, w.owner_id AS work_owner_id
                FROM pages p
                JOIN chapter_revisions r ON r.id = p.chapter_revision_id
                JOIN chapters c ON c.id = r.chapter_id
                JOIN works w ON w.id = c.work_id
                WHERE p.image_asset_id = ? OR p.thumbnail_asset_id = ?
                """;
        return jdbc.query(sql, (rs, i) -> new PageAssetRef(
                rs.getObject("page_id", UUID.class),
                rs.getObject("revision_id", UUID.class),
                rs.getString("revision_status"),
                rs.getObject("work_id", UUID.class),
                rs.getString("work_visibility"),
                rs.getString("work_status"),
                rs.getObject("work_owner_id", UUID.class)), assetId, assetId).stream().findFirst();
    }

    public record PageAssetRef(UUID pageId, UUID revisionId, String revisionStatus,
                               UUID workId, String workVisibility, String workStatus,
                               UUID workOwnerId) {
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
