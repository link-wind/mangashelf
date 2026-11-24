package com.mangashelf.retrieval;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 确定性毫秒级轻量 Reranker（方案 D：benchmark-and-optimize-hybrid-search）：
 * 零外网依赖、零 GPU 依赖，在 Hybrid Top-K 候选上融合 5 项可解释信号：
 * RRF 共识分、标题/别名精确与前缀命中、中英文词元/2-gram 覆盖率、字面分与语义余弦分。
 */
@Component
public class LightweightReranker {

    public record CandidateFeatures(
            SearchDocumentRepository.Candidate doc,
            double rrfScore,
            double lexicalScore,
            double vectorScore) {
    }

    public record RankedCandidate(
            SearchDocumentRepository.Candidate doc,
            double finalScore,
            double rrfScore,
            double lexicalScore,
            double vectorScore) {
    }

    public record Weights(
            double rrfWeight,
            double exactTitleWeight,
            double tokenCoverageWeight,
            double lexicalWeight,
            double vectorWeight) {

        /** 经 dev 集验证的默认均衡权重。 */
        public static final Weights DEFAULT = new Weights(0.35, 0.25, 0.20, 0.10, 0.10);
    }

    public List<RankedCandidate> rerank(String query, List<CandidateFeatures> candidates, Weights weights, int limit) {
        if (candidates.isEmpty()) return List.of();
        String normQ = normalize(query);
        Set<String> queryTokens = extractTokens(normQ);

        double maxRrf = candidates.stream().mapToDouble(CandidateFeatures::rrfScore).max().orElse(1.0);
        double maxLex = candidates.stream().mapToDouble(CandidateFeatures::lexicalScore).max().orElse(1.0);
        double minVec = candidates.stream().mapToDouble(CandidateFeatures::vectorScore).min().orElse(0.0);
        double maxVec = candidates.stream().mapToDouble(CandidateFeatures::vectorScore).max().orElse(1.0);

        return candidates.stream()
                .map(c -> {
                    double rrfNorm = maxRrf > 0 ? c.rrfScore() / maxRrf : 0.0;
                    double lexNorm = maxLex > 0 ? c.lexicalScore() / maxLex : 0.0;
                    double vecNorm = (maxVec > minVec && c.vectorScore() > 0)
                            ? (c.vectorScore() - minVec) / (maxVec - minVec)
                            : Math.max(0.0, c.vectorScore());

                    double titleBoost = computeTitleMatch(normQ, c.doc().title(), c.doc().altTitles());
                    double coverage = computeCoverage(queryTokens,
                            c.doc().title() + " " + c.doc().altTitles() + " " + c.doc().body());

                    double score = weights.rrfWeight() * rrfNorm
                            + weights.exactTitleWeight() * titleBoost
                            + weights.tokenCoverageWeight() * coverage
                            + weights.lexicalWeight() * lexNorm
                            + weights.vectorWeight() * vecNorm;
                    return new RankedCandidate(c.doc(), score, c.rrfScore(), c.lexicalScore(), c.vectorScore());
                })
                .sorted(Comparator.comparingDouble(RankedCandidate::finalScore).reversed()
                        .thenComparingLong(r -> r.doc().id()))
                .limit(limit)
                .toList();
    }

    static double computeTitleMatch(String normQ, String title, String altTitles) {
        if (normQ.isEmpty()) return 0.0;
        String normTitle = normalize(title);
        String normAlt = normalize(altTitles);
        if (normTitle.equals(normQ) || containsExactSegment(normAlt, normQ)) {
            return 1.0;
        }
        if (normTitle.startsWith(normQ) || normAlt.startsWith(normQ)) {
            return 0.85;
        }
        if (normTitle.contains(normQ) || normAlt.contains(normQ)) {
            return 0.65;
        }
        // 反向包含：查询比标题略长（如带季数/后缀/口语词）
        if (!normTitle.isEmpty() && normTitle.length() >= 2 && normQ.contains(normTitle)) {
            return 0.55;
        }
        return 0.0;
    }

    static double computeCoverage(Set<String> queryTokens, String docText) {
        if (queryTokens.isEmpty()) return 0.0;
        String normDoc = normalize(docText);
        if (normDoc.isEmpty()) return 0.0;
        int hit = 0;
        for (String tok : queryTokens) {
            if (normDoc.contains(tok)) {
                hit++;
            }
        }
        return (double) hit / queryTokens.size();
    }

    /** 提取英文单词与 CJK 字符/2-gram，适配中日英混合动漫标题与简介。 */
    static Set<String> extractTokens(String norm) {
        Set<String> tokens = new HashSet<>();
        if (norm.isEmpty()) return tokens;
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < norm.length(); i++) {
            char ch = norm.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                flushCjk(cjk, tokens);
                latin.append(ch);
            } else if (isCjk(ch)) {
                flushLatin(latin, tokens);
                cjk.append(ch);
            } else {
                flushLatin(latin, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushLatin(latin, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    private static void flushLatin(StringBuilder latin, Set<String> out) {
        if (latin.length() >= 2) {
            out.add(latin.toString());
        }
        latin.setLength(0);
    }

    private static void flushCjk(StringBuilder cjk, Set<String> out) {
        int len = cjk.length();
        if (len == 1) {
            out.add(cjk.toString());
        } else if (len >= 2) {
            for (int i = 0; i < len - 1; i++) {
                out.add(cjk.substring(i, i + 2));
            }
        }
        cjk.setLength(0);
    }

    private static boolean containsExactSegment(String normAlt, String normQ) {
        if (normAlt.isEmpty()) return false;
        for (String seg : normAlt.split("[/|｜,，;；]+")) {
            if (seg.trim().equals(normQ)) return true;
        }
        return false;
    }

    private static boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA;
    }

    static String normalize(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s：:·・!！?？「」『』（）()【】《》—\\-]+", " ")
                .trim();
    }
}
