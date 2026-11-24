package com.mangashelf.retrieval;

import com.mangashelf.config.ApiException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 站内统一文档混合检索服务（benchmark-and-optimize-hybrid-search）：
 * 显式支持 A(LEXICAL) / B(VECTOR) / C(HYBRID_RRF) / D(HYBRID_RERANK) 四种策略，
 * 同一实现同时服务线上接口与离线 Benchmark，保证评测与线上行为完全一致。
 */
@Service
public class HybridDocumentSearchService {

    public static final int DEFAULT_RRF_K = 40;
    private static final int MAX_LIMIT = 50;

    public enum Strategy {
        LEXICAL, VECTOR, HYBRID_RRF, HYBRID_RERANK;

        public static Strategy parse(String raw, Strategy defaultValue) {
            if (raw == null || raw.isBlank()) return defaultValue;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "lexical", "a" -> LEXICAL;
                case "vector", "b" -> VECTOR;
                case "hybrid", "hybrid_rrf", "rrf", "c" -> HYBRID_RRF;
                case "rerank", "hybrid_rerank", "d" -> HYBRID_RERANK;
                default -> throw new ApiException(400, "INVALID_ARGUMENT",
                        "未知检索策略：" + raw + "（可选 lexical/vector/hybrid_rrf/hybrid_rerank）");
            };
        }
    }

    public record SearchOptions(int rrfK, int candidateMultiplier, int rerankPoolSize,
                                LightweightReranker.Weights rerankWeights) {
        public static final SearchOptions DEFAULT = new SearchOptions(
                DEFAULT_RRF_K, 4, 30, LightweightReranker.Weights.DEFAULT);
    }

    public record DocumentHit(
            String docType,
            UUID targetId,
            String title,
            String altTitles,
            String snippet,
            String sourceLabel,
            double score) {
    }

    public record DocumentSearchResponse(
            String q,
            String strategy,
            boolean semanticEnabled,
            List<DocumentHit> hits) {
    }

    private final SearchDocumentRepository docs;
    private final LightweightReranker reranker;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final SearchMetrics metrics;

    public HybridDocumentSearchService(SearchDocumentRepository docs,
                                       LightweightReranker reranker,
                                       ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                       SearchMetrics metrics) {
        this.docs = docs;
        this.reranker = reranker;
        this.embeddingModelProvider = embeddingModelProvider;
        this.metrics = metrics;
    }

    public DocumentSearchResponse search(UUID viewerId, String query, Strategy strategy,
                                         String docTypeFilter, int limit) {
        return search(viewerId, query, strategy, docTypeFilter, limit, SearchOptions.DEFAULT);
    }

    public DocumentSearchResponse search(UUID viewerId, String query, Strategy strategy,
                                         String docTypeFilter, int limit, SearchOptions options) {
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        boolean needsVector = strategy != Strategy.LEXICAL;
        if (needsVector && model == null) {
            throw new ApiException(503, "SEMANTIC_UNAVAILABLE",
                    "语义检索未配置 Embedding 模型；请使用 lexical 策略。");
        }

        long startNs = System.nanoTime();
        String modeLabel = needsVector ? "semantic" : "title";
        int candidateLimit = Math.max(effectiveLimit * options.candidateMultiplier(), 40);

        List<SearchDocumentRepository.Candidate> lexicalHits = strategy == Strategy.VECTOR
                ? List.of()
                : docs.searchLexical(viewerId, query, docTypeFilter, candidateLimit);

        List<SearchDocumentRepository.Candidate> vectorHits = List.of();
        if (needsVector) {
            long embedStart = System.nanoTime();
            float[] vec = model.embed("query: " + query.trim());
            metrics.recordEmbed("query", Duration.ofNanos(System.nanoTime() - embedStart));
            String vecLiteral = SearchDocumentSyncService.formatVector(vec);
            vectorHits = docs.searchVector(viewerId, vecLiteral,
                    SearchDocumentSyncService.DEFAULT_EMBEDDING_MODEL, docTypeFilter, candidateLimit);
        }

        List<DocumentHit> finalHits = switch (strategy) {
            case LEXICAL -> lexicalHits.stream().limit(effectiveLimit).map(this::toHit).toList();
            case VECTOR -> vectorHits.stream().limit(effectiveLimit).map(this::toHit).toList();
            case HYBRID_RRF -> fuseRrf(lexicalHits, vectorHits, options.rrfK(), effectiveLimit);
            case HYBRID_RERANK -> fuseAndRerank(query, lexicalHits, vectorHits, options, effectiveLimit);
        };

        metrics.recordSearch(modeLabel, strategy.name().toLowerCase(Locale.ROOT),
                Duration.ofNanos(System.nanoTime() - startNs));
        return new DocumentSearchResponse(query, strategy.name().toLowerCase(Locale.ROOT),
                model != null, finalHits);
    }

    private List<DocumentHit> fuseRrf(List<SearchDocumentRepository.Candidate> lexical,
                                      List<SearchDocumentRepository.Candidate> vector,
                                      int rrfK, int limit) {
        Map<Long, SearchDocumentRepository.Candidate> byId = new LinkedHashMap<>();
        Map<Long, Double> rrf = new LinkedHashMap<>();
        accumulateRrf(lexical, rrfK, byId, rrf);
        accumulateRrf(vector, rrfK, byId, rrf);

        return rrf.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparingLong(Map.Entry::getKey))
                .limit(limit)
                .map(e -> toHit(byId.get(e.getKey()), e.getValue()))
                .toList();
    }

    private List<DocumentHit> fuseAndRerank(String query,
                                            List<SearchDocumentRepository.Candidate> lexical,
                                            List<SearchDocumentRepository.Candidate> vector,
                                            SearchOptions options, int limit) {
        Map<Long, SearchDocumentRepository.Candidate> byId = new LinkedHashMap<>();
        Map<Long, Double> rrf = new LinkedHashMap<>();
        Map<Long, Double> lexScore = new LinkedHashMap<>();
        Map<Long, Double> vecScore = new LinkedHashMap<>();

        int rank = 1;
        for (var c : lexical) {
            byId.putIfAbsent(c.id(), c);
            lexScore.put(c.id(), c.score());
            rrf.merge(c.id(), 1.0 / (options.rrfK() + rank++), Double::sum);
        }
        rank = 1;
        for (var c : vector) {
            byId.putIfAbsent(c.id(), c);
            vecScore.put(c.id(), c.score());
            rrf.merge(c.id(), 1.0 / (options.rrfK() + rank++), Double::sum);
        }

        int poolSize = Math.max(options.rerankPoolSize(), limit);
        List<LightweightReranker.CandidateFeatures> pool = rrf.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparingLong(Map.Entry::getKey))
                .limit(poolSize)
                .map(e -> new LightweightReranker.CandidateFeatures(
                        byId.get(e.getKey()),
                        e.getValue(),
                        lexScore.getOrDefault(e.getKey(), 0.0),
                        vecScore.getOrDefault(e.getKey(), 0.0)))
                .toList();

        return reranker.rerank(query, pool, options.rerankWeights(), limit).stream()
                .map(r -> toHit(r.doc(), r.finalScore()))
                .toList();
    }

    private static void accumulateRrf(List<SearchDocumentRepository.Candidate> list, int rrfK,
                                      Map<Long, SearchDocumentRepository.Candidate> byId,
                                      Map<Long, Double> rrf) {
        int rank = 1;
        for (var c : list) {
            byId.putIfAbsent(c.id(), c);
            rrf.merge(c.id(), 1.0 / (rrfK + rank++), Double::sum);
        }
    }

    private DocumentHit toHit(SearchDocumentRepository.Candidate c) {
        return toHit(c, c.score());
    }

    private DocumentHit toHit(SearchDocumentRepository.Candidate c, double score) {
        String snippet = c.body() == null ? "" : c.body().trim();
        if (snippet.length() > 140) snippet = snippet.substring(0, 140) + "…";
        return new DocumentHit(c.docType(), c.targetId(), c.title(), c.altTitles(),
                snippet, c.sourceLabel(), score);
    }
}
