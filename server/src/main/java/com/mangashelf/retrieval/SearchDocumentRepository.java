package com.mangashelf.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 站内统一检索文档仓储（V28 search_documents）：
 * 显式 SQL + 前置权限过滤（is_public = true OR owner_id = :viewerId），
 * 确保私人作品在字面、语义、混合与重排四组策略中均零泄漏。
 */
@Repository
public class SearchDocumentRepository {

    public static final String TYPE_WORK = "WORK";
    public static final String TYPE_ANIMATION = "ANIMATION";
    public static final String TYPE_ANIME_CATALOG = "ANIME_CATALOG";
    public static final String TYPE_MANGA_CATALOG = "MANGA_CATALOG";

    private final JdbcTemplate jdbc;

    public SearchDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DocumentUpsert(
            String docType,
            UUID targetId,
            UUID ownerId,
            boolean isPublic,
            String title,
            String altTitles,
            String body,
            String embeddingLiteral,
            String embeddingModel,
            String sourceLabel) {
    }

    public record Candidate(
            long id,
            String docType,
            UUID targetId,
            String title,
            String altTitles,
            String body,
            String sourceLabel,
            double score) {
    }

    public void upsert(DocumentUpsert doc) {
        String safeTitle = doc.title() == null ? "" : doc.title().trim();
        String safeAlt = doc.altTitles() == null ? "" : doc.altTitles().trim();
        String safeBody = doc.body() == null ? "" : doc.body().trim();
        String searchText = buildSearchText(safeTitle, safeAlt, safeBody);
        jdbc.update("""
                INSERT INTO search_documents (
                    doc_type, target_id, owner_id, is_public,
                    title, alt_titles, body, search_text,
                    embedding, embedding_model, source_label, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?, ?, now())
                ON CONFLICT (doc_type, target_id) DO UPDATE SET
                    owner_id = EXCLUDED.owner_id,
                    is_public = EXCLUDED.is_public,
                    title = EXCLUDED.title,
                    alt_titles = EXCLUDED.alt_titles,
                    body = EXCLUDED.body,
                    search_text = EXCLUDED.search_text,
                    embedding = EXCLUDED.embedding,
                    embedding_model = EXCLUDED.embedding_model,
                    source_label = EXCLUDED.source_label,
                    updated_at = now()
                """,
                doc.docType(), doc.targetId(), doc.ownerId(), doc.isPublic(),
                safeTitle, safeAlt, safeBody, searchText,
                doc.embeddingLiteral(), doc.embeddingModel(),
                doc.sourceLabel() == null ? "" : doc.sourceLabel());
    }

    public int deleteByTarget(String docType, UUID targetId) {
        return jdbc.update("DELETE FROM search_documents WHERE doc_type = ? AND target_id = ?", docType, targetId);
    }

    public long countAll() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM search_documents", Long.class);
        return c == null ? 0L : c;
    }

    /**
     * 方案 A：pg_trgm 字面召回（授权前置过滤）。
     * 结合整串 trigram、word_similarity（解决长文档分母稀释）与多词拆分命中（解决空格分隔多关键词），
     * 复用既有 gin_trgm_ops 索引。
     */
    public List<Candidate> searchLexical(UUID viewerId, String query, String docTypeFilter, int limit) {
        String q = query.trim();
        String likePattern = "%" + likeEscape(q) + "%";
        List<String> terms = splitQueryTerms(q);
        boolean hasType = docTypeFilter != null && !docTypeFilter.isBlank();

        StringBuilder scoreExpr = new StringBuilder("""
                (
                    GREATEST(similarity(title, ?), similarity(alt_titles, ?)) * 1.8
                  + word_similarity(?, search_text) * 1.4
                  + CASE WHEN title ILIKE ? ESCAPE '\\' THEN 0.70 ELSE 0.0 END
                  + CASE WHEN alt_titles ILIKE ? ESCAPE '\\' THEN 0.55 ELSE 0.0 END
                  + CASE WHEN body ILIKE ? ESCAPE '\\' THEN 0.30 ELSE 0.0 END
                """);
        StringBuilder matchClause = new StringBuilder("""
                (
                    search_text % ?
                 OR title % ?
                 OR ? <% search_text
                 OR search_text ILIKE ? ESCAPE '\\'
                """);

        List<Object> scoreArgs = new java.util.ArrayList<>();
        scoreArgs.add(q);
        scoreArgs.add(q);
        scoreArgs.add(q);
        scoreArgs.add(likePattern);
        scoreArgs.add(likePattern);
        scoreArgs.add(likePattern);

        List<Object> matchArgs = new java.util.ArrayList<>();
        matchArgs.add(q);
        matchArgs.add(q);
        matchArgs.add(q);
        matchArgs.add(likePattern);

        for (String term : terms) {
            String tLike = "%" + likeEscape(term) + "%";
            scoreExpr.append("""
                    + CASE WHEN title ILIKE ? ESCAPE '\\' THEN 0.38
                           WHEN alt_titles ILIKE ? ESCAPE '\\' THEN 0.30
                           WHEN body ILIKE ? ESCAPE '\\' THEN 0.22
                           ELSE 0.0 END
                    """);
            scoreArgs.add(tLike);
            scoreArgs.add(tLike);
            scoreArgs.add(tLike);

            matchClause.append(" OR search_text ILIKE ? ESCAPE '\\'");
            matchArgs.add(tLike);
            if (term.length() >= 3) {
                matchClause.append(" OR ? <% search_text");
                matchArgs.add(term);
            }
        }
        scoreExpr.append(")");
        matchClause.append(")");

        String sql = "SELECT id, doc_type, target_id, title, alt_titles, body, source_label, "
                + scoreExpr + " AS score "
                + "FROM search_documents "
                + "WHERE (is_public = true OR owner_id = ?) "
                + (hasType ? "AND doc_type = ? " : "")
                + "AND " + matchClause + " "
                + "ORDER BY score DESC, id ASC "
                + "LIMIT ?";

        List<Object> allArgs = new java.util.ArrayList<>(scoreArgs);
        allArgs.add(viewerId);
        if (hasType) allArgs.add(docTypeFilter);
        allArgs.addAll(matchArgs);
        allArgs.add(limit);

        return jdbc.query(sql, (rs, i) -> new Candidate(
                rs.getLong("id"),
                rs.getString("doc_type"),
                rs.getObject("target_id", UUID.class),
                rs.getString("title"),
                rs.getString("alt_titles"),
                rs.getString("body"),
                rs.getString("source_label"),
                rs.getDouble("score")
        ), allArgs.toArray());
    }

    private static List<String> splitQueryTerms(String query) {
        String[] raw = query.split("[\\s：:·・/|,，;；!?！？()（）【】《》\\-]+");
        List<String> terms = new java.util.ArrayList<>();
        for (String r : raw) {
            String t = r.trim();
            if (t.length() >= 2 && !terms.contains(t)) {
                terms.add(t);
            }
            if (terms.size() >= 6) break;
        }
        return terms;
    }

    /**
     * 方案 B：pgvector 余弦语义召回（授权前置 + 同模型过滤，严禁混用不同模型向量）。
     */
    public List<Candidate> searchVector(UUID viewerId, String vectorLiteral, String embeddingModel,
                                        String docTypeFilter, int limit) {
        String typeClause = (docTypeFilter == null || docTypeFilter.isBlank()) ? "" : " AND doc_type = ? ";
        String sql = """
                SELECT id, doc_type, target_id, title, alt_titles, body, source_label,
                       1.0 - (embedding <=> ?::vector) AS score
                  FROM search_documents
                 WHERE (is_public = true OR owner_id = ?)
                   AND embedding IS NOT NULL
                   AND embedding_model = ?
                """ + typeClause + """
                 ORDER BY embedding <=> ?::vector ASC, id ASC
                 LIMIT ?
                """;
        Object[] args = (docTypeFilter == null || docTypeFilter.isBlank())
                ? new Object[]{vectorLiteral, viewerId, embeddingModel, vectorLiteral, limit}
                : new Object[]{vectorLiteral, viewerId, embeddingModel, docTypeFilter, vectorLiteral, limit};
        return jdbc.query(sql, (rs, i) -> new Candidate(
                rs.getLong("id"),
                rs.getString("doc_type"),
                rs.getObject("target_id", UUID.class),
                rs.getString("title"),
                rs.getString("alt_titles"),
                rs.getString("body"),
                rs.getString("source_label"),
                rs.getDouble("score")
        ), args);
    }

    /** 测量主表与各索引实际磁盘体积（用于 Benchmark 成本报告）。 */
    public Map<String, Long> relationSizesBytes() {
        Map<String, Long> sizes = new LinkedHashMap<>();
        List<String> rels = List.of(
                "search_documents",
                "idx_search_docs_text_trgm",
                "idx_search_docs_title_trgm",
                "idx_search_docs_embedding_hnsw",
                "idx_search_docs_visibility"
        );
        for (String rel : rels) {
            Long bytes = jdbc.queryForObject("SELECT pg_relation_size(?::regclass)", Long.class, rel);
            sizes.put(rel, bytes == null ? 0L : bytes);
        }
        return sizes;
    }

    public static String buildSearchText(String title, String altTitles, String body) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) sb.append(title.trim());
        if (altTitles != null && !altTitles.isBlank()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(altTitles.trim());
        }
        if (body != null && !body.isBlank()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(body.trim());
        }
        return sb.toString();
    }

    private static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
