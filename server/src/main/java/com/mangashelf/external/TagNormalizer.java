package com.mangashelf.external;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 番剧与漫画题材标签归一化与清洗：
 * - 拆分逗号/顿号/斜杠/空白分隔的多标签串；
 * - 去除尾部「漫画/动漫/动画」冗余后缀；
 * - 合并常见同义题材（如 喜剧→搞笑、爱情→恋爱、魔幻→奇幻）；
 * - 过滤年份、地区、介质、状态与平台营销噪声词。
 */
public final class TagNormalizer {

    private static final Pattern SPLIT_PATTERN = Pattern.compile("[,，、/|｜；;\\s]+");
    private static final Pattern EDGE_PUNCT = Pattern.compile("^[#·•「」【】\\[\\]()（）《》]+|[#·•「」【】\\[\\]()（）《》]+$");
    private static final Pattern STARTS_WITH_DIGIT = Pattern.compile("^[0-9].*");
    private static final Pattern CONTAINS_YEAR = Pattern.compile(".*(19|20)\\d{2}.*");
    private static final Pattern EPISODE_LABEL = Pattern.compile("^(更新|第\\d+|全\\d+|共\\d+).*");

    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("喜剧", "搞笑"),
            Map.entry("幽默", "搞笑"),
            Map.entry("爆笑", "搞笑"),
            Map.entry("爱情", "恋爱"),
            Map.entry("言情", "恋爱"),
            Map.entry("魔幻", "奇幻"),
            Map.entry("神魔", "奇幻"),
            Map.entry("机战", "机甲"),
            Map.entry("体育", "运动"),
            Map.entry("惊悚", "恐怖"),
            Map.entry("灵异", "恐怖"),
            Map.entry("古装", "古风"),
            Map.entry("武侠格斗", "武侠"),
            Map.entry("悬疑推理", "悬疑"),
            Map.entry("推理", "悬疑"),
            Map.entry("格斗", "动作"),
            Map.entry("战斗", "动作"),
            Map.entry("日常搞笑", "搞笑"),
            Map.entry("萌系", "治愈")
    );

    private static final Set<String> NOISE_TAGS = Set.of(
            // 介质与大类
            "动画", "动漫", "番剧", "国产动漫", "日韩动漫", "欧美动漫", "港台动漫", "海外动漫",
            "国产动画", "日本动画", "国漫", "日漫", "韩漫", "美漫", "漫画", "彩漫", "条漫", "全彩",
            "TV", "tv", "WEB", "web", "OVA", "ova", "OAD", "oad", "SP", "剧场版", "电影", "新番",
            "单行本", "短篇", "泡面番", "动态漫画", "有声漫",
            // 地区与语言
            "日本", "中国", "大陆", "中国大陆", "国产", "欧美", "韩国", "台湾", "香港", "港台",
            "日韩", "海外", "内地", "中漫",
            "美国", "法国", "英国", "日语", "国语", "汉语普通话", "中文", "粤语", "英语",
            // 改编来源与平台/状态营销词
            "漫改", "轻改", "轻小说改", "漫画改", "游戏改", "小说改", "原创", "原创动画",
            "连载中", "连载", "已完结", "完结", "更新中", "签约", "独家", "付费", "VIP", "vip",
            "限免", "免费", "新作", "人气", "热门", "推荐", "精选", "精品", "排行", "经典",
            "高清", "正版", "授权", "官方", "全年龄", "热血少年", "未分类", "其他", "其它",
            "普通话", "中字", "中文字幕", "完结动画", "日本动漫", "大陆动漫", "欧美动画"
    );

    private TagNormalizer() {}

    /** 将单个原始标签清洗为规范题材名；命中噪声或非法长度返回 null。 */
    public static String canonicalize(String raw) {
        if (raw == null) return null;
        String s = EDGE_PUNCT.matcher(raw.trim()).replaceAll("").trim();
        if (s.isEmpty() || NOISE_TAGS.contains(s)) return null;
        if (s.length() >= 4 && (s.endsWith("漫画") || s.endsWith("动漫") || s.endsWith("动画"))) {
            String stripped = s.substring(0, s.length() - 2);
            if (stripped.length() >= 2 && !NOISE_TAGS.contains(stripped)) {
                s = stripped;
            }
        }
        String mapped = SYNONYMS.get(s);
        if (mapped != null) {
            s = mapped;
        }
        if (s.length() < 2 || s.length() > 10) return null;
        if (NOISE_TAGS.contains(s)) return null;
        if (STARTS_WITH_DIGIT.matcher(s).matches()) return null;
        if (CONTAINS_YEAR.matcher(s).matches()) return null;
        if (EPISODE_LABEL.matcher(s).matches()) return null;
        return s;
    }

    /** 清洗并去重一组原始标签（保留顺序）。 */
    public static List<String> normalize(Collection<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : rawTags) {
            if (raw == null || raw.isBlank()) continue;
            for (String token : SPLIT_PATTERN.split(raw)) {
                String clean = canonicalize(token);
                if (clean != null) {
                    out.add(clean);
                }
            }
        }
        return List.copyOf(out);
    }

    /** 解析一个或多个分隔字符串（如 MacCMS vod_class / vod_tag）并归一化。 */
    public static List<String> parseDelimited(String... rawFields) {
        if (rawFields == null || rawFields.length == 0) return List.of();
        List<String> list = new ArrayList<>(rawFields.length);
        for (String f : rawFields) {
            if (f != null && !f.isBlank()) {
                list.add(f);
            }
        }
        return normalize(list);
    }

    /** 返回规范题材对应的所有查询别名（含自身与指向它的同义词），用于数组重叠查询兼容历史数据。 */
    public static String[] aliasesFor(String tag) {
        if (tag == null || tag.isBlank()) return new String[0];
        String trimmed = tag.trim();
        String canonical = canonicalize(trimmed);
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        aliases.add(trimmed);
        if (canonical != null) {
            aliases.add(canonical);
            for (var entry : SYNONYMS.entrySet()) {
                if (canonical.equals(entry.getValue())) {
                    aliases.add(entry.getKey());
                }
            }
        }
        return aliases.toArray(new String[0]);
    }

    /** 解析四位年份（1950..2035），非法值返回 null。 */
    public static Integer parseYear(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var m = Pattern.compile("(19\\d{2}|20\\d{2})").matcher(raw.trim());
        if (!m.find()) return null;
        int y = Integer.parseInt(m.group(1));
        return (y >= 1950 && y <= 2035) ? y : null;
    }
}
