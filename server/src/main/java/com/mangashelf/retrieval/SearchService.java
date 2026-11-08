package com.mangashelf.retrieval;

import com.mangashelf.animation.AnimationRepository;
import com.mangashelf.catalog.CatalogRepository;
import com.mangashelf.catalog.Work;
import com.mangashelf.identity.CurrentUser;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 混合召回（DESIGN 15.3）：标题/章节名 + OCR 字面（pg_trgm）+ 语义（V6 后）三路
 * RRF 合并（k=60），按（作品、章节、页）去重；授权过滤在 SQL 前置。
 */
@Service
public class SearchService {

    private static final int RRF_K = 60;
    private static final int MAX_LIMIT = 30;

    private final RetrievalRepository retrievals;
    private final CatalogRepository catalog;
    private final AnimationRepository animations;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    public SearchService(RetrievalRepository retrievals, CatalogRepository catalog,
                         AnimationRepository animations,
                         ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.retrievals = retrievals;
        this.catalog = catalog;
        this.animations = animations;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    public record SearchHit(String kind, UUID workId, String workTitle, UUID chapterId,
                            UUID revisionId, int pageOrdinal, String chapterTitle,
                            String snippet, boolean hasAnimation) {
    }

    public record SearchResult(String q, boolean semanticEnabled, List<SearchHit> hits) {
    }

    public boolean semanticEnabled() {
        return embeddingModelProvider.getIfAvailable() != null;
    }

    public SearchResult search(CurrentUser viewer, String q, boolean hasAnimation, int limit) {
        int effectiveLimit = Math.min(limit, MAX_LIMIT);
        // 两路召回（语义在 V6 后加入第三路）
        List<RetrievalRepository.PageHit> chunkHits = retrievals.searchChunks(viewer.id(), q, effectiveLimit);
        List<RetrievalRepository.PageHit> semanticHits = List.of();
        EmbeddingModel embedding = embeddingModelProvider.getIfAvailable();
        if (embedding != null) {
            float[] vec = embedding.embed("query: " + q);
            semanticHits = retrievals.searchChunksSemantic(viewer.id(), vectorString(vec), effectiveLimit);
        }
        List<Work> workHits = catalog.searchWorksByTitle(viewer.id(), q, effectiveLimit);

        // RRF 合并（同 (work, chapter, page) 去重，title 行以 (work, null-chapter) 表示）
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, RetrievalRepository.PageHit> pageByKey = new LinkedHashMap<>();
        Map<String, Work> workByKey = new LinkedHashMap<>();
        int rank = 1;
        for (RetrievalRepository.PageHit hit : chunkHits) {
            scores.merge("p:" + hit.workId() + ":" + hit.chapterId() + ":" + hit.ordinal(),
                    1.0 / (RRF_K + rank++), Double::sum);
        }
        rank = 1;
        for (RetrievalRepository.PageHit hit : semanticHits) {
            scores.merge("p:" + hit.workId() + ":" + hit.chapterId() + ":" + hit.ordinal(),
                    1.0 / (RRF_K + rank++), Double::sum);
        }
        rank = 1;
        for (var work : workHits) {
            scores.merge("w:" + work.id(), 1.0 / (RRF_K + rank++), Double::sum);
        }
        for (RetrievalRepository.PageHit hit : chunkHits) {
            pageByKey.putIfAbsent("p:" + hit.workId() + ":" + hit.chapterId() + ":" + hit.ordinal(), hit);
        }
        for (RetrievalRepository.PageHit hit : semanticHits) {
            pageByKey.putIfAbsent("p:" + hit.workId() + ":" + hit.chapterId() + ":" + hit.ordinal(), hit);
        }
        for (var work : workHits) {
            workByKey.putIfAbsent("w:" + work.id(), work);
        }

        List<Map.Entry<String, Double>> ordered = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(effectiveLimit)
                .toList();
        List<SearchHit> hits = new ArrayList<>();
        for (var entry : ordered) {
            String key = entry.getKey();
            if (key.startsWith("p:")) {
                var hit = pageByKey.get(key);
                boolean animated = !animations.findPageAdaptations(hit.revisionId(), hit.ordinal()).isEmpty();
                if (hasAnimation && !animated) continue;
                hits.add(new SearchHit("page", hit.workId(), hit.workTitle(), hit.chapterId(),
                        hit.revisionId(), hit.ordinal(), hit.chapterTitle(),
                        snippet(hit.text(), q), animated));
            } else {
                var work = workByKey.get(key);
                boolean animated = animationsHasSourceForWork(work.id());
                if (hasAnimation && !animated) continue;
                hits.add(new SearchHit("work", work.id(), work.title(), null, null, 0, null,
                        work.description(), animated));
            }
        }
        return new SearchResult(q, semanticEnabled(), hits);
    }

    /** 作品行"有动画"：存在 PUBLISHED 动画的来源引用该作品的章节（实时判定，DESIGN 15.3）。 */
    private boolean animationsHasSourceForWork(UUID workId) {
        return animations.countPublishedForWork(workId) > 0;
    }

    /** 片段摘要：命中词附近 ±40 字符。 */
    static String snippet(String text, String q) {
        if (text == null || text.isBlank()) return "";
        String normalized = text.replace('\n', ' ');
        int idx = normalized.toLowerCase().indexOf(q.toLowerCase());
        if (idx < 0) {
            return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "…";
        }
        int from = Math.max(0, idx - 40);
        int to = Math.min(normalized.length(), idx + q.length() + 40);
        return (from > 0 ? "…" : "") + normalized.substring(from, to) + (to < normalized.length() ? "…" : "");
    }

    private static String vectorString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }
}
