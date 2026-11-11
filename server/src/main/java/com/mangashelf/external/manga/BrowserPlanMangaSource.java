package com.mangashelf.external.manga;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 声明式浏览器漫画源（M32）：规则行里的 browserPlan 描述 URL 模板与选择器，
 * 数据提取交给 Playwright 任务（manga.plan），展示仍走站内统一 UI。
 * 新增站点只加规则行，不改 Java。
 */
public class BrowserPlanMangaSource implements MangaSource {

    private final String id;
    private final String name;
    private final String note;
    private final String language;
    private final String planJson;
    private final JsonNode plan;
    private final Set<Capability> capabilities;
    private final MangaBrowserResolveService browser;
    private final ObjectMapper objectMapper;
    private final String cookie;
    private final String storageState;

    public BrowserPlanMangaSource(String id, String name, String note, String rulesJson,
                                  MangaBrowserResolveService browser, ObjectMapper objectMapper,
                                  String cookie, String storageState) {
        this.id = id;
        this.name = name;
        this.note = note;
        this.browser = browser;
        this.objectMapper = objectMapper;
        this.cookie = cookie;
        this.storageState = storageState;
        JsonNode root = objectMapper.readTree(rulesJson);
        this.language = root.path("language").asText("zh");
        this.plan = root.path("browserPlan");
        this.planJson = plan.toString();
        EnumSet<Capability> caps = EnumSet.noneOf(Capability.class);
        if (!plan.path("search").isMissingNode()) caps.add(Capability.SEARCH);
        if (!plan.path("detail").isMissingNode()) caps.add(Capability.DETAIL);
        if (!plan.path("pages").isMissingNode()) caps.add(Capability.READ);
        if (!plan.path("rank").isMissingNode()) caps.add(Capability.RANK);
        this.capabilities = caps;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String note() {
        return note;
    }

    @Override
    public String language() {
        return language;
    }

    @Override
    public Set<Capability> capabilities() {
        return capabilities;
    }

    @Override
    public List<SearchItem> search(String query, String lang, int limit) throws Exception {
        Map<String, String> vars = Map.of("query", java.net.URLEncoder.encode(
                query == null ? "" : query, java.nio.charset.StandardCharsets.UTF_8));
        JsonNode result = browser.resolvePlan("search", planJson, vars, cookie, storageState);
        List<SearchItem> out = new ArrayList<>();
        for (JsonNode item : result.path("items")) {
            if (out.size() >= limit) break;
            String cid = item.path("id").asText("");
            String title = firstLine(item.path("title").asText(""));
            if (cid.isBlank() || title.isBlank()) continue;
            String cover = item.path("cover").asText("");
            out.add(new SearchItem(id, cid, title, "",
                    cover.isBlank() ? null : cover, null));
        }
        return out;
    }

    @Override
    public Detail detail(String mangaId, String lang) throws Exception {
        JsonNode result = browser.resolvePlan("detail", planJson,
                Map.of("id", mangaId), cookie, storageState);
        JsonNode single = result.path("single");
        String title = firstLine(single.path("title").asText(mangaId));
        String cover = single.path("cover").asText("");
        String desc = single.path("description").asText("");

        List<ChapterItem> chapters = new ArrayList<>();
        JsonNode slots = plan.path("detail").path("slots");
        if (!slots.isMissingNode() && result.has("last_slot") && !result.path("last_slot").isNull()) {
            int last = result.path("last_slot").asInt(0);
            String labelTemplate = slots.path("labelTemplate").asText("第{slot}话");
            for (int slot = 0; slot <= last; slot++) {
                String label = labelTemplate.replace("{slot}", String.valueOf(slot + 1));
                chapters.add(new ChapterItem(mangaId + "|" + slot, label, label, language, -1, false));
            }
        } else {
            for (JsonNode item : result.path("items")) {
                String cid = item.path("id").asText("");
                String label = item.path("title").asText("");
                if (cid.isBlank()) continue;
                // API/列表章节的 id 已是站点章节标识（如嬉皮 hid），不再前缀作品 id
                chapters.add(new ChapterItem(cid, label, label, language, -1, false));
            }
        }
        return new Detail(new SearchItem(id, mangaId, title, "", cover.isBlank() ? null : cover, null),
                desc, "", null, List.of(), chapters);
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i >= 0 ? s.substring(0, i) : s).trim();
    }

    @Override
    public Pages pages(String chapterId) throws Exception {
        if (chapterId == null || chapterId.isBlank() || chapterId.length() > 300
                || !chapterId.matches("[A-Za-z0-9_.|~\\-]+")) {
            throw new IllegalArgumentException("章节 ID 格式不合法：" + chapterId);
        }
        // 通用变量：chapterId 原样可用；若为 {mangaId}|{slot} 形态再拆出两段
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("chapterId", chapterId);
        int sep = chapterId.indexOf('|');
        if (sep > 0 && sep < chapterId.length() - 1) {
            vars.put("comicId", chapterId.substring(0, sep));
            vars.put("slot", chapterId.substring(sep + 1));
        }
        JsonNode result = browser.resolvePlan("pages", planJson, vars, cookie, storageState);
        List<String> pages = new ArrayList<>();
        for (JsonNode p : result.path("pages")) {
            String url = p.asText("");
            if (!url.isBlank()) pages.add(url);
        }
        if (pages.isEmpty()) throw new IllegalStateException("该章节未解析到页图");
        return new Pages(pages, List.of(), false);
    }
}
