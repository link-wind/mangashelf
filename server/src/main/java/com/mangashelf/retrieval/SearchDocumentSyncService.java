package com.mangashelf.retrieval;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 站内结构化数据 → search_documents 同步服务（零 OCR）：
 * 聚合 works / animations / external_catalog_items(+bangumi_subjects) / manga_catalog_items。
 */
@Service
public class SearchDocumentSyncService {

    public static final String DEFAULT_EMBEDDING_MODEL = "e5-small-v2";

    private final JdbcTemplate jdbc;
    private final SearchDocumentRepository docs;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final SearchMetrics metrics;

    public SearchDocumentSyncService(JdbcTemplate jdbc, SearchDocumentRepository docs,
                                     ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                     SearchMetrics metrics) {
        this.jdbc = jdbc;
        this.docs = docs;
        this.embeddingModelProvider = embeddingModelProvider;
        this.metrics = metrics;
    }

    public record SyncSummary(int works, int animations, int animeCatalog, int mangaCatalog, boolean embedded) {
    }

    public SyncSummary syncAll(boolean computeEmbeddings) {
        EmbeddingModel model = computeEmbeddings ? embeddingModelProvider.getIfAvailable() : null;
        int w = syncWorks(model);
        int a = syncAnimations(model);
        int ac = syncAnimeCatalog(model);
        int mc = syncMangaCatalog(model);
        return new SyncSummary(w, a, ac, mc, model != null);
    }

    private int syncWorks(EmbeddingModel model) {
        List<SearchDocumentRepository.DocumentUpsert> list = jdbc.query("""
                SELECT id, owner_id, title, COALESCE(description, '') AS description, visibility, status
                  FROM works
                """, (rs, i) -> {
            String title = rs.getString("title");
            String desc = rs.getString("description");
            boolean isPublic = "PUBLIC".equals(rs.getString("visibility"))
                    && "PUBLISHED".equals(rs.getString("status"));
            return toUpsert(SearchDocumentRepository.TYPE_WORK, rs.getObject("id", UUID.class),
                    rs.getObject("owner_id", UUID.class), isPublic, title, "", desc, "站内作品", model);
        });
        list.forEach(docs::upsert);
        return list.size();
    }

    private int syncAnimations(EmbeddingModel model) {
        List<SearchDocumentRepository.DocumentUpsert> list = jdbc.query("""
                SELECT id, owner_id, title, COALESCE(description, '') AS description, visibility, status
                  FROM animations
                """, (rs, i) -> {
            String title = rs.getString("title");
            String desc = rs.getString("description");
            boolean isPublic = "PUBLIC".equals(rs.getString("visibility"))
                    && "PUBLISHED".equals(rs.getString("status"));
            return toUpsert(SearchDocumentRepository.TYPE_ANIMATION, rs.getObject("id", UUID.class),
                    rs.getObject("owner_id", UUID.class), isPublic, title, "", desc, "站内动画", model);
        });
        list.forEach(docs::upsert);
        return list.size();
    }

    private int syncAnimeCatalog(EmbeddingModel model) {
        List<SearchDocumentRepository.DocumentUpsert> list = jdbc.query("""
                SELECT e.id, e.name, e.category_name, e.remarks,
                       COALESCE(b.name, '') AS bgm_name,
                       COALESCE(b.name_cn, '') AS bgm_name_cn,
                       COALESCE(b.summary, '') AS bgm_summary,
                       b.tags AS bgm_tags
                  FROM external_catalog_items e
                  LEFT JOIN bangumi_subjects b ON b.subject_id = e.bangumi_subject_id
                """, (rs, i) -> {
            String name = rs.getString("name");
            String bgmName = rs.getString("bgm_name");
            String bgmCn = rs.getString("bgm_name_cn");
            String alt = joinDistinct(bgmCn, bgmName, name);
            String tags = joinPgArray(rs.getArray("bgm_tags"));
            String body = joinNonBlank(" ",
                    rs.getString("category_name"),
                    tags,
                    rs.getString("bgm_summary"),
                    rs.getString("remarks"));
            return toUpsert(SearchDocumentRepository.TYPE_ANIME_CATALOG, rs.getObject("id", UUID.class),
                    null, true, name, alt, body, "番剧目录", model);
        });
        list.forEach(docs::upsert);
        return list.size();
    }

    private int syncMangaCatalog(EmbeddingModel model) {
        List<SearchDocumentRepository.DocumentUpsert> list = jdbc.query("""
                SELECT id, source, name, alt_title, remarks, tags
                  FROM manga_catalog_items
                """, (rs, i) -> {
            String tags = joinPgArray(rs.getArray("tags"));
            String body = joinNonBlank(" ", tags, rs.getString("remarks"));
            return toUpsert(SearchDocumentRepository.TYPE_MANGA_CATALOG, rs.getObject("id", UUID.class),
                    null, true, rs.getString("name"), rs.getString("alt_title"),
                    body, "漫画目录·" + rs.getString("source"), model);
        });
        list.forEach(docs::upsert);
        return list.size();
    }

    public SearchDocumentRepository.DocumentUpsert toUpsert(
            String docType, UUID targetId, UUID ownerId, boolean isPublic,
            String title, String altTitles, String body, String sourceLabel, EmbeddingModel model) {
        String vectorLiteral = null;
        String modelName = null;
        if (model != null) {
            String passage = "passage: " + SearchDocumentRepository.buildSearchText(title, altTitles, body);
            long start = System.nanoTime();
            float[] vec = model.embed(passage);
            metrics.recordEmbed("index", Duration.ofNanos(System.nanoTime() - start));
            vectorLiteral = formatVector(vec);
            modelName = DEFAULT_EMBEDDING_MODEL;
        }
        return new SearchDocumentRepository.DocumentUpsert(
                docType, targetId, ownerId, isPublic, title, altTitles, body,
                vectorLiteral, modelName, sourceLabel);
    }

    public static String formatVector(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private static String joinPgArray(Array arr) throws java.sql.SQLException {
        if (arr == null) return "";
        Object raw = arr.getArray();
        if (raw instanceof String[] sa && sa.length > 0) {
            return String.join(" ", sa);
        }
        return "";
    }

    private static String joinDistinct(String a, String b, String excludeTitle) {
        StringBuilder sb = new StringBuilder();
        for (String s : new String[]{a, b}) {
            if (s != null && !s.isBlank() && !s.equalsIgnoreCase(excludeTitle)) {
                if (!sb.isEmpty()) sb.append(" / ");
                sb.append(s.trim());
            }
        }
        return sb.toString();
    }

    private static String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (!sb.isEmpty()) sb.append(sep);
                sb.append(p.trim());
            }
        }
        return sb.toString();
    }
}
