package com.mangashelf.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 检索模块显式 SQL（DESIGN 15）：批次、索引块与授权范围内召回。 */
@Repository
public class RetrievalRepository {

    private final JdbcTemplate jdbc;

    public RetrievalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── 批次 ──

    public UUID insertGeneration(UUID revisionId, String pipelineVersion, String manifestSha) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO index_generations (id, chapter_revision_id, pipeline_version,
                                              source_manifest_sha256)
                VALUES (?, ?, ?, ?)
                """, id, revisionId, pipelineVersion, manifestSha);
        return id;
    }

    public Optional<GenerationRow> findGeneration(UUID id) {
        return jdbc.query("SELECT * FROM index_generations WHERE id = ?", (rs, i) ->
                new GenerationRow(rs.getObject("id", UUID.class),
                        rs.getObject("chapter_revision_id", UUID.class),
                        rs.getString("status"), rs.getString("embedding_model"),
                        rs.getObject("dimensions") == null ? null : rs.getInt("dimensions"),
                        rs.getString("pipeline_version"),
                        rs.getString("source_manifest_sha256")), id).stream().findFirst();
    }

    public Optional<GenerationRow> findActiveGeneration(UUID revisionId) {
        return jdbc.query("""
                SELECT * FROM index_generations
                WHERE chapter_revision_id = ? AND status = 'ACTIVE'
                """, (rs, i) -> new GenerationRow(rs.getObject("id", UUID.class),
                rs.getObject("chapter_revision_id", UUID.class),
                rs.getString("status"), rs.getString("embedding_model"),
                rs.getObject("dimensions") == null ? null : rs.getInt("dimensions"),
                rs.getString("pipeline_version"),
                rs.getString("source_manifest_sha256")), revisionId).stream().findFirst();
    }

    public void markGenerationStatus(UUID id, String status, String errorCode, String errorMessage) {
        jdbc.update("""
                UPDATE index_generations SET status = ?, error_code = ?, error_message = ?, updated_at = now()
                WHERE id = ?
                """, status, errorCode, errorMessage, id);
    }

    /** 批次原子激活：旧 ACTIVE 降为 READY，新批次置 ACTIVE（DESIGN 15.5，需同一事务）。 */
    public void activateGeneration(UUID revisionId, UUID newGenerationId) {
        jdbc.update("""
                UPDATE index_generations SET status = 'READY', updated_at = now()
                WHERE chapter_revision_id = ? AND status = 'ACTIVE'
                """, revisionId);
        jdbc.update("""
                UPDATE index_generations SET status = 'ACTIVE', updated_at = now()
                WHERE id = ?
                """, newGenerationId);
    }

    public void setGenerationEmbedding(UUID id, String model, int dimensions) {
        jdbc.update("""
                UPDATE index_generations SET embedding_model = ?, dimensions = ?, updated_at = now()
                WHERE id = ?
                """, model, dimensions, id);
    }

    public record GenerationRow(UUID id, UUID chapterRevisionId, String status,
                                String embeddingModel, Integer dimensions,
                                String pipelineVersion, String sourceManifestSha256) {
    }

    // ── 索引块 ──

    public void replaceChunks(UUID generationId, UUID revisionId, List<ChunkRow> chunks) {
        jdbc.update("DELETE FROM search_chunks WHERE index_generation_id = ?", generationId);
        for (ChunkRow c : chunks) {
            jdbc.update("""
                    INSERT INTO search_chunks (index_generation_id, chapter_revision_id, page_id,
                                               chunk_kind, text, embedding)
                    VALUES (?, ?, ?, 'page', ?, ?::vector)
                    """, generationId, revisionId, c.pageId(), c.text(), c.embedding());
        }
    }

    public record ChunkRow(UUID pageId, String text, String embedding) {
    }

    // ── 召回（授权过滤前置，DESIGN 3.3/15.3）──

    public record PageHit(long chunkId, UUID pageId, UUID revisionId, UUID chapterId,
                          UUID workId, String workTitle, String chapterTitle, int ordinal,
                          String text, double similarity) {
    }

    /** OCR 字面召回：ACTIVE 批次 + PUBLISHED 版本 + 授权范围 + trgm 相似度。 */
    public List<PageHit> searchChunks(UUID viewerId, String q, int limit) {
        return jdbc.query("""
                SELECT sc.id AS chunk_id, p.id AS page_id, r.id AS revision_id, c.id AS chapter_id,
                       w.id AS work_id, w.title AS work_title, c.title AS chapter_title,
                       p.ordinal, sc.text, similarity(sc.text, ?) AS sim
                FROM search_chunks sc
                JOIN index_generations g ON g.id = sc.index_generation_id AND g.status = 'ACTIVE'
                JOIN chapter_revisions r ON r.id = sc.chapter_revision_id AND r.status = 'PUBLISHED'
                JOIN pages p ON p.id = sc.page_id
                JOIN chapters c ON c.id = r.chapter_id
                JOIN works w ON w.id = c.work_id
                WHERE (w.owner_id = ? OR (w.visibility = 'PUBLIC' AND w.status = 'PUBLISHED'))
                  AND sc.text % ?
                ORDER BY sim DESC
                LIMIT ?
                """, (rs, i) -> new PageHit(rs.getLong("chunk_id"),
                rs.getObject("page_id", UUID.class), rs.getObject("revision_id", UUID.class),
                rs.getObject("chapter_id", UUID.class), rs.getObject("work_id", UUID.class),
                rs.getString("work_title"), rs.getString("chapter_title"), rs.getInt("ordinal"),
                rs.getString("text"), rs.getDouble("sim")),
                q, viewerId, q, limit);
    }

    /** 语义召回（V6 后启用）：cosine 距离升序。 */
    public List<PageHit> searchChunksSemantic(UUID viewerId, String queryVector, int limit) {
        return jdbc.query("""
                SELECT sc.id AS chunk_id, p.id AS page_id, r.id AS revision_id, c.id AS chapter_id,
                       w.id AS work_id, w.title AS work_title, c.title AS chapter_title,
                       p.ordinal, sc.text, 1 - (sc.embedding <=> ?::vector) AS sim
                FROM search_chunks sc
                JOIN index_generations g ON g.id = sc.index_generation_id AND g.status = 'ACTIVE'
                JOIN chapter_revisions r ON r.id = sc.chapter_revision_id AND r.status = 'PUBLISHED'
                JOIN pages p ON p.id = sc.page_id
                JOIN chapters c ON c.id = r.chapter_id
                JOIN works w ON w.id = c.work_id
                WHERE (w.owner_id = ? OR (w.visibility = 'PUBLIC' AND w.status = 'PUBLISHED'))
                  AND sc.embedding IS NOT NULL
                ORDER BY sc.embedding <=> ?::vector
                LIMIT ?
                """, (rs, i) -> new PageHit(rs.getLong("chunk_id"),
                rs.getObject("page_id", UUID.class), rs.getObject("revision_id", UUID.class),
                rs.getObject("chapter_id", UUID.class), rs.getObject("work_id", UUID.class),
                rs.getString("work_title"), rs.getString("chapter_title"), rs.getInt("ordinal"),
                rs.getString("text"), rs.getDouble("sim")),
                queryVector, viewerId, queryVector, limit);
    }
}
