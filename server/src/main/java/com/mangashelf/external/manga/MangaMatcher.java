package com.mangashelf.external.manga;

/** 标题归一化与相似度（M18）：供综合榜跨源同作合并使用（MangaDiscoveryService）。 */
final class MangaMatcher {

    private MangaMatcher() {
    }

    /** 标题归一化：小写、去括号注释/标点/空白、去季期后缀。 */
    static String normalize(String s) {
        if (s == null) return "";
        String v = s.toLowerCase(java.util.Locale.ROOT);
        v = v.replaceAll("（[^）]*）|\\([^)]*\\)|\\[[^]]*]|【[^】]*】", "");
        v = v.replaceAll("[\\s\\p{P}]", "");
        v = v.replaceAll("第[一二三四五六七八九十百0-9]+[季期部篇话集卷]", "");
        v = v.replaceAll("(season|vol|volume)[0-9]+", "");
        return v;
    }

    /** 字符二元组 Jaccard（与动画侧同算法，对中文短标题稳定）。 */
    static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        var sa = bigrams(a);
        var sb = bigrams(b);
        var inter = new java.util.HashSet<>(sa);
        inter.retainAll(sb);
        var union = new java.util.HashSet<>(sa);
        union.addAll(sb);
        return inter.size() / (double) union.size();
    }

    private static java.util.Set<String> bigrams(String s) {
        var out = new java.util.HashSet<String>();
        for (int i = 0; i + 1 < s.length(); i++) out.add(s.substring(i, i + 2));
        return out;
    }
}
