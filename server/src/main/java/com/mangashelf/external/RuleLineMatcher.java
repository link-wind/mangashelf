package com.mangashelf.external;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外部规则源的季期感知标题匹配器：用于将 7sefun 等 HTML 规则源精准挂载到现有番剧目录条目，
 * 避免第一季/第二季或 TV/剧场版跨源错配。
 */
final class RuleLineMatcher {

    private static final double MIN_SIMILARITY = 0.85;

    private static final Pattern CN_SEASON = Pattern.compile("第\\s*([0-9一二三四五六七八九十]+)\\s*[季期部]");
    private static final Pattern EN_SEASON = Pattern.compile(
            "(?:season\\s*(\\d+)|\\bs(\\d+)\\b|(\\d+)(?:nd|rd|th)\\s*season)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ROMAN_TAIL = Pattern.compile("(?:^|[\\s：:·\\-])(Ⅱ|Ⅲ|Ⅳ|Ⅱ期|III|II)\\s*$");
    private static final Pattern MOVIE_PAT = Pattern.compile("剧场版|電影|电影|movie|总集篇|總集篇", Pattern.CASE_INSENSITIVE);
    private static final Pattern OVA_PAT = Pattern.compile("\\b(?:ova|oad|sp)\\b|特别篇|特別篇|番外", Pattern.CASE_INSENSITIVE);

    private RuleLineMatcher() {}

    /** 提取季号（未显式标注时返回 null，表示第 1 季或单季作品）。 */
    static Integer extractSeason(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) return null;
        String s = TraditionalToSimplified.toSimplified(rawTitle).trim();
        Matcher cn = CN_SEASON.matcher(s);
        if (cn.find()) {
            return parseNum(cn.group(1));
        }
        Matcher en = EN_SEASON.matcher(s);
        if (en.find()) {
            for (int g = 1; g <= 3; g++) {
                if (en.group(g) != null) return parseNum(en.group(g));
            }
        }
        Matcher roman = ROMAN_TAIL.matcher(s);
        if (roman.find()) {
            String tok = roman.group(1);
            if ("Ⅱ".equals(tok) || "Ⅱ期".equals(tok) || "II".equals(tok)) return 2;
            if ("Ⅲ".equals(tok) || "III".equals(tok)) return 3;
            if ("Ⅳ".equals(tok)) return 4;
        }
        return null;
    }

    /** 区分 TV / 剧场版 / OVA，防止正片挂到剧场版或特典上。 */
    static String extractVariant(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) return "TV";
        String s = TraditionalToSimplified.toSimplified(rawTitle);
        if (MOVIE_PAT.matcher(s).find()) return "MOVIE";
        if (OVA_PAT.matcher(s).find()) return "OVA";
        return "TV";
    }

    /** 归一化基础片名（繁简统一 + 去季期后缀与标点）。 */
    static String baseTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) return "";
        String simplified = TraditionalToSimplified.toSimplified(rawTitle);
        String stripped = ROMAN_TAIL.matcher(simplified).replaceAll("");
        return BangumiService.normalizeTitle(stripped);
    }

    /** 构造按优先级排列的搜索关键词（先完整简体名，再补去季期基础名以防站点命名差异）。 */
    static List<String> searchKeywords(String bangumiNameCn, String catalogName) {
        Set<String> keywords = new LinkedHashSet<>();
        addKeyword(keywords, bangumiNameCn);
        addKeyword(keywords, catalogName);
        String baseCn = stripSeasonSuffix(bangumiNameCn);
        String baseCat = stripSeasonSuffix(catalogName);
        addKeyword(keywords, baseCn);
        addKeyword(keywords, baseCat);
        return new ArrayList<>(keywords);
    }

    /** 在候选搜索结果中选出与目标番剧同季、同形态且基础片名最高匹配的一项。 */
    static Optional<RuleEngine.SearchResultItem> findBestMatch(
            List<String> targetTitles, List<RuleEngine.SearchResultItem> candidates) {
        if (targetTitles == null || targetTitles.isEmpty() || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        RuleEngine.SearchResultItem best = null;
        double bestScore = -1.0;

        for (RuleEngine.SearchResultItem candidate : candidates) {
            if (candidate == null || candidate.name() == null || candidate.url() == null) continue;
            String candBase = baseTitle(candidate.name());
            if (candBase.isEmpty()) continue;
            Integer candSeason = extractSeason(candidate.name());
            String candVariant = extractVariant(candidate.name());

            for (String target : targetTitles) {
                if (target == null || target.isBlank()) continue;
                String targetBase = baseTitle(target);
                if (targetBase.isEmpty()) continue;
                Integer targetSeason = extractSeason(target);
                String targetVariant = extractVariant(target);

                if (!targetVariant.equals(candVariant)) continue;
                if (!seasonCompatible(targetSeason, candSeason)) continue;

                double sim = targetBase.equals(candBase)
                        ? 1.0
                        : BangumiService.bigramSimilarity(targetBase, candBase);
                if (sim < MIN_SIMILARITY) continue;

                // 精确同季加微小优先级，确保「第2季」优先于模糊近似项
                double score = sim + (targetSeason != null && targetSeason.equals(candSeason) ? 0.05 : 0.0);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean seasonCompatible(Integer targetSeason, Integer candSeason) {
        int t = targetSeason == null ? 1 : targetSeason;
        int c = candSeason == null ? 1 : candSeason;
        return t == c;
    }

    private static void addKeyword(Set<String> set, String raw) {
        if (raw == null || raw.isBlank()) return;
        String clean = TraditionalToSimplified.toSimplified(raw).trim();
        if (clean.length() >= 2) {
            set.add(clean);
        }
    }

    private static String stripSeasonSuffix(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String s = TraditionalToSimplified.toSimplified(raw).trim();
        s = CN_SEASON.matcher(s).replaceAll("");
        s = EN_SEASON.matcher(s).replaceAll("");
        s = ROMAN_TAIL.matcher(s).replaceAll("");
        return s.replaceAll("[（(][^）)]*[）)]", "").trim();
    }

    private static Integer parseNum(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            return Integer.parseInt(token.trim());
        } catch (NumberFormatException ignored) {
            return switch (token.trim()) {
                case "一" -> 1;
                case "二" -> 2;
                case "三" -> 3;
                case "四" -> 4;
                case "五" -> 5;
                case "六" -> 6;
                case "七" -> 7;
                case "八" -> 8;
                case "九" -> 9;
                case "十" -> 10;
                default -> null;
            };
        }
    }
}
