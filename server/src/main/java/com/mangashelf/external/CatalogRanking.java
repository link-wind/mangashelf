package com.mangashelf.external;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 番剧与漫画统一地区分级（JP > CN > KR > OVERSEAS）与多源评分/热度归一化工具。
 * 置信度（regionConfidence）越大越权威，高置信度不被低置信度覆盖：
 * - 40：AniList countryOfOrigin / Bangumi 精准判定
 * - 30：哔哩官方 area_id / Bangumi 原名脚本与地区标签
 * - 20：MacCMS vod_area / 拷贝漫画 region 字段 / 标题字符显式判定
 * - 10：分类/数据源先验兜底
 */
public final class CatalogRanking {

    public static final String REGION_JP = "JP";
    public static final String REGION_CN = "CN";
    public static final String REGION_KR = "KR";
    public static final String REGION_OVERSEAS = "OVERSEAS";
    public static final String REGION_UNKNOWN = "UNKNOWN";

    public static final int CONF_NONE = 0;
    public static final int CONF_PRIOR = 10;
    public static final int CONF_SOURCE_FIELD = 20;
    public static final int CONF_OFFICIAL_AREA = 30;
    public static final int CONF_AUTHORITATIVE = 40;

    private static final double BAYESIAN_PRIOR_MEAN = 7.0;
    private static final double BAYESIAN_PRIOR_VOTES = 50.0;

    private static final Pattern KANA = Pattern.compile("[\\u3040-\\u30ff]");
    private static final Pattern HANGUL = Pattern.compile("[\\uac00-\\ud7af]");

    public record RegionInfo(String region, int rank, int confidence) {
        public static RegionInfo of(String region, int confidence) {
            String norm = normalizeRegionValue(region);
            return new RegionInfo(norm, regionRank(norm), REGION_UNKNOWN.equals(norm) ? CONF_NONE : confidence);
        }
    }

    public record FusedRating(double rawScore, int votes, double bayesianRating) {}

    private CatalogRanking() {}

    public static int regionRank(String region) {
        if (region == null) return 5;
        return switch (region.trim().toUpperCase(Locale.ROOT)) {
            case REGION_JP -> 1;
            case REGION_CN -> 2;
            case REGION_KR -> 3;
            case REGION_OVERSEAS -> 4;
            default -> 5;
        };
    }

    public static String normalizeRegionValue(String raw) {
        if (raw == null || raw.isBlank()) return REGION_UNKNOWN;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case REGION_JP -> REGION_JP;
            case REGION_CN -> REGION_CN;
            case REGION_KR -> REGION_KR;
            case REGION_OVERSEAS -> REGION_OVERSEAS;
            default -> REGION_UNKNOWN;
        };
    }

    /** 校验查询参数 region（支持 JP/CN/KR/OVERSEAS，空串返回 null）。 */
    public static String normalizeRegionFilter(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case REGION_JP, REGION_CN, REGION_KR, REGION_OVERSEAS -> upper;
            default -> throw new IllegalArgumentException("未知地区筛选：" + raw);
        };
    }

    /** AniList countryOfOrigin（ISO 3166-1 alpha-2）映射为统一地区，置信度 40。 */
    public static RegionInfo fromAniListCountry(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return RegionInfo.of(REGION_UNKNOWN, CONF_NONE);
        }
        return switch (countryCode.trim().toUpperCase(Locale.ROOT)) {
            case "JP" -> RegionInfo.of(REGION_JP, CONF_AUTHORITATIVE);
            case "CN", "TW", "HK" -> RegionInfo.of(REGION_CN, CONF_AUTHORITATIVE);
            case "KR" -> RegionInfo.of(REGION_KR, CONF_AUTHORITATIVE);
            default -> RegionInfo.of(REGION_OVERSEAS, CONF_AUTHORITATIVE);
        };
    }

    /** 从 Bangumi 条目原名 + 标签推导地区，置信度 30。 */
    public static RegionInfo fromBangumiSubject(String origName, List<String> tags) {
        if (tags != null) {
            for (String raw : tags) {
                if (raw == null) continue;
                String t = raw.trim();
                if (t.equals("国产") || t.equals("中国") || t.equals("国漫") || t.equals("中国大陆")
                        || t.equals("港台") || t.equals("台湾") || t.equals("香港")) {
                    return RegionInfo.of(REGION_CN, CONF_OFFICIAL_AREA);
                }
                if (t.equals("韩国") || t.equals("韩漫") || t.equals("韩国动画")) {
                    return RegionInfo.of(REGION_KR, CONF_OFFICIAL_AREA);
                }
                if (t.equals("欧美") || t.equals("美国") || t.equals("英国") || t.equals("法国")
                        || t.equals("美漫") || t.equals("加拿大") || t.equals("俄罗斯")) {
                    return RegionInfo.of(REGION_OVERSEAS, CONF_OFFICIAL_AREA);
                }
                if (t.equals("日本") || t.equals("日漫")) {
                    return RegionInfo.of(REGION_JP, CONF_OFFICIAL_AREA);
                }
            }
        }
        if (origName != null && !origName.isBlank()) {
            if (HANGUL.matcher(origName).find()) {
                return RegionInfo.of(REGION_KR, CONF_OFFICIAL_AREA);
            }
            if (KANA.matcher(origName).find()) {
                return RegionInfo.of(REGION_JP, CONF_OFFICIAL_AREA);
            }
        }
        return RegionInfo.of(REGION_UNKNOWN, CONF_NONE);
    }

    /** 从 MacCMS 资源站 vod_area + category_id + 标题/标签判定番剧地区。 */
    public static RegionInfo fromVodArea(String vodArea, int categoryId, String title, List<String> tags) {
        if (vodArea != null && !vodArea.isBlank()) {
            String a = vodArea.trim();
            if (a.contains("日本")) {
                return RegionInfo.of(REGION_JP, CONF_SOURCE_FIELD);
            }
            if (a.contains("韩国") || a.contains("南韩")) {
                return RegionInfo.of(REGION_KR, CONF_SOURCE_FIELD);
            }
            if (a.contains("大陆") || a.contains("内地") || a.contains("中国") || a.contains("国产")
                    || a.contains("香港") || a.contains("台湾") || a.contains("港台")) {
                return RegionInfo.of(REGION_CN, CONF_SOURCE_FIELD);
            }
            if (a.contains("欧美") || a.contains("美国") || a.contains("英国") || a.contains("法国")
                    || a.contains("德国") || a.contains("加拿大") || a.contains("俄罗斯") || a.contains("澳洲")
                    || a.contains("西班牙") || a.contains("意大利") || a.contains("泰国") || a.contains("海外")
                    || a.contains("其他") || a.contains("其它")) {
                return RegionInfo.of(REGION_OVERSEAS, CONF_SOURCE_FIELD);
            }
        }
        if (tags != null) {
            for (String t : tags) {
                if ("韩国".equals(t) || "韩漫".equals(t) || "韩国动画".equals(t)) {
                    return RegionInfo.of(REGION_KR, CONF_SOURCE_FIELD);
                }
            }
        }
        if (title != null && HANGUL.matcher(title).find()) {
            return RegionInfo.of(REGION_KR, CONF_SOURCE_FIELD);
        }
        return switch (categoryId) {
            case 29, 32 -> RegionInfo.of(REGION_CN, CONF_PRIOR);
            case 31, 33 -> RegionInfo.of(REGION_OVERSEAS, CONF_PRIOR);
            case 30 -> RegionInfo.of(REGION_JP, CONF_PRIOR);
            default -> RegionInfo.of(REGION_UNKNOWN, CONF_NONE);
        };
    }

    /** 哔哩哔哩漫画官方 area_id 映射（2=日本, 1=大陆, 6=韩国, 5=其他），置信度 30。 */
    public static RegionInfo fromBilibiliAreaId(int areaId) {
        return switch (areaId) {
            case 2 -> RegionInfo.of(REGION_JP, CONF_OFFICIAL_AREA);
            case 1 -> RegionInfo.of(REGION_CN, CONF_OFFICIAL_AREA);
            case 6 -> RegionInfo.of(REGION_KR, CONF_OFFICIAL_AREA);
            case 5 -> RegionInfo.of(REGION_OVERSEAS, CONF_OFFICIAL_AREA);
            default -> RegionInfo.of(REGION_UNKNOWN, CONF_NONE);
        };
    }

    /** 拷贝漫画条目 region 字段映射（缺失时回落 JP 先验）。 */
    public static RegionInfo fromCopyMangaItem(JsonNode item, String title, List<String> tags) {
        if (item != null) {
            JsonNode regionNode = item.path("region");
            String display = regionNode.isObject() ? regionNode.path("display").asText("") : regionNode.asText("");
            int value = regionNode.isObject() ? regionNode.path("value").asInt(-1) : -1;
            if (!display.isBlank()) {
                if (display.contains("日")) return RegionInfo.of(REGION_JP, CONF_SOURCE_FIELD);
                if (display.contains("韩") || display.contains("韓")) return RegionInfo.of(REGION_KR, CONF_SOURCE_FIELD);
                if (display.contains("国") || display.contains("國") || display.contains("港") || display.contains("台")) {
                    return RegionInfo.of(REGION_CN, CONF_SOURCE_FIELD);
                }
                if (display.contains("欧") || display.contains("美") || display.contains("歐")) {
                    return RegionInfo.of(REGION_OVERSEAS, CONF_SOURCE_FIELD);
                }
            }
            if (value == 0) return RegionInfo.of(REGION_JP, CONF_SOURCE_FIELD);
            if (value == 1) return RegionInfo.of(REGION_KR, CONF_SOURCE_FIELD);
            if (value == 2) return RegionInfo.of(REGION_OVERSEAS, CONF_SOURCE_FIELD);
        }
        if (tags != null) {
            for (String t : tags) {
                if ("韩漫".equals(t) || "韩国".equals(t)) return RegionInfo.of(REGION_KR, CONF_SOURCE_FIELD);
                if ("国漫".equals(t) || "国产".equals(t)) return RegionInfo.of(REGION_CN, CONF_SOURCE_FIELD);
                if ("美漫".equals(t) || "欧美".equals(t)) return RegionInfo.of(REGION_OVERSEAS, CONF_SOURCE_FIELD);
            }
        }
        if (title != null && HANGUL.matcher(title).find()) {
            return RegionInfo.of(REGION_KR, CONF_SOURCE_FIELD);
        }
        return RegionInfo.of(REGION_JP, CONF_PRIOR);
    }

    /** 漫画源默认地区先验（置信度 10）。 */
    public static RegionInfo defaultMangaRegionForSource(String source) {
        if (source == null) return RegionInfo.of(REGION_UNKNOWN, CONF_NONE);
        return switch (source) {
            case "copymanga" -> RegionInfo.of(REGION_JP, CONF_PRIOR);
            case "tencent", "bilibili", "baozimh", "hipmh" -> RegionInfo.of(REGION_CN, CONF_PRIOR);
            default -> RegionInfo.of(REGION_UNKNOWN, CONF_NONE);
        };
    }

    /** 提取 MacCMS 资源站原生评分（优先豆瓣分，过滤 0 与满 10 占位脏值）。 */
    public static Double parseVodScore(JsonNode vod) {
        if (vod == null) return null;
        for (String field : new String[]{"vod_douban_score", "vod_score"}) {
            String raw = vod.path(field).asText("").trim();
            if (raw.isBlank()) continue;
            try {
                double v = Double.parseDouble(raw);
                if (v >= 1.0 && v <= 9.9) {
                    return Math.round(v * 10.0) / 10.0;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    public static int parseVodHits(JsonNode vod) {
        if (vod == null) return 0;
        int hits = vod.path("vod_hits").asInt(0);
        return Math.max(0, hits);
    }

    /** 拷贝漫画 popular 字段对数归一化到 0–100（10^7 封顶满分）。 */
    public static double popularityFromCopyPopular(long popular) {
        if (popular <= 0) return 0.0;
        double score = Math.log10(1.0 + popular) / 7.0 * 100.0;
        return Math.round(Math.min(100.0, Math.max(0.0, score)) * 10.0) / 10.0;
    }

    /** 榜单页位次归一化到 0–100（第 1 名约 98，越靠后平滑衰减）。 */
    public static double popularityFromRank(int zeroBasedRank, int totalHorizon) {
        if (zeroBasedRank < 0) return 0.0;
        int horizon = Math.max(totalHorizon, 100);
        double ratio = Math.min(1.0, zeroBasedRank / (double) horizon);
        double score = 100.0 * Math.pow(1.0 - ratio * 0.85, 1.35);
        return Math.round(Math.min(100.0, Math.max(10.0, score)) * 10.0) / 10.0;
    }

    /** 标准贝叶斯加权评分：避免 3 人打 9.8 霸榜。 */
    public static Double bayesianRating(Double rawScore, int votes) {
        if (rawScore == null || rawScore <= 0.0) return null;
        double v = Math.max(votes, 10);
        double b = (v / (v + BAYESIAN_PRIOR_VOTES)) * rawScore
                + (BAYESIAN_PRIOR_VOTES / (v + BAYESIAN_PRIOR_VOTES)) * BAYESIAN_PRIOR_MEAN;
        return Math.round(b * 100.0) / 100.0;
    }

    /**
     * 融合 Bangumi（中文社区，权重 1.5x）与 AniList（全球社区，averageScore/10，人气/25 折算票数）。
     */
    public static FusedRating fuseRatings(Double bgmScore, int bgmVotes, Integer aniAvgScore100, Integer aniPopularity) {
        Double validBgm = (bgmScore != null && bgmScore > 0.0 && bgmScore <= 10.0) ? bgmScore : null;
        Double validAni = (aniAvgScore100 != null && aniAvgScore100 > 0 && aniAvgScore100 <= 100)
                ? (aniAvgScore100 / 10.0) : null;
        int safeBgmVotes = validBgm != null ? Math.max(bgmVotes, 10) : 0;
        int safeAniVotes = validAni != null ? Math.max((aniPopularity == null ? 0 : aniPopularity) / 25, 10) : 0;

        if (validBgm == null && validAni == null) {
            return null;
        }
        double raw;
        int totalVotes;
        if (validBgm != null && validAni != null) {
            double wBgm = safeBgmVotes * 1.5;
            double wAni = safeAniVotes;
            raw = (validBgm * wBgm + validAni * wAni) / (wBgm + wAni);
            totalVotes = safeBgmVotes + safeAniVotes;
        } else if (validBgm != null) {
            raw = validBgm;
            totalVotes = safeBgmVotes;
        } else {
            raw = validAni;
            totalVotes = safeAniVotes;
        }
        double roundedRaw = Math.round(raw * 10.0) / 10.0;
        Double bayesian = bayesianRating(roundedRaw, totalVotes);
        return new FusedRating(roundedRaw, totalVotes, bayesian == null ? roundedRaw : bayesian);
    }
}
