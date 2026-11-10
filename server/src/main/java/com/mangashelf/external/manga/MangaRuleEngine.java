package com.mangashelf.external.manga;

import com.jayway.jsonpath.JsonPath;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 漫画规则源引擎（M23）：解释 manga_source_rules.rules JSON。
 * HTML 模式（jsoup CSS 选择器）/ API 模式（JSONPath）与动画侧 RuleEngine 同设计；
 * pages 另支持 decode 模式（具名钩子，闭合集合）。不执行 JS——字段提取之外无逻辑，
 * 解码钩子的求值器字符白名单（见 TencentNonceDecoder）。
 */
@Service
public class MangaRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(MangaRuleEngine.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern VAR = Pattern.compile("\\{([a-zA-Z]+)\\}");

    private final ObjectMapper objectMapper;
    private final Map<String, MangaPageDecoder> decoders;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT).build();

    /** 详情解析缓存：作品页体积大（腾讯约 1~2.5MB），TTL 10 分钟。 */
    private record Cached(String text, Instant expiresAt) {}

    private final ConcurrentHashMap<String, Cached> detailCache = new ConcurrentHashMap<>();

    public MangaRuleEngine(ObjectMapper objectMapper, List<MangaPageDecoder> decoderBeans) {
        this.objectMapper = objectMapper;
        this.decoders = new LinkedHashMap<>();
        for (MangaPageDecoder d : decoderBeans) {
            decoders.put(d.name(), d);
        }
    }

    // ── 榜单 / 搜索 ──

    public List<MangaSource.RankItem> rank(String rulesJson, String sourceId, int limit) throws Exception {
        JsonNode root = objectMapper.readTree(rulesJson);
        JsonNode rule = section(root, "rank");
        JsonNode bootstrap = root.path("bootstrapVersion");
        boolean api = "api".equals(rule.path("mode").asText("html"));
        // 源无 detail 节 = 详情/阅读不可用 → 条目标记站外（点击跳 externalUrl）
        boolean readable = !root.path("detail").isMissingNode()
                && !root.path("pages").isMissingNode();
        String externalBase = root.path("externalUrl").asText("");
        List<MangaSource.RankItem> out = new ArrayList<>();
        if (api) {
            String listPath = rule.path("list").asText("$[*]");
            for (Map<String, Object> it : fetchApi(rule.path("url").asText(),
                    root.path("headers"), Map.of(), listPath, bootstrap)) {
                if (out.size() >= limit) break;
                Map<String, String> f = apiFields(rule.path("fields"), it);
                String id = f.getOrDefault("id", "");
                if (id.isBlank()) continue;
                out.add(new MangaSource.RankItem(sourceId, id, f.getOrDefault("title", ""),
                        f.containsKey("authors") ? Arrays.stream(f.get("authors").split("[,，]"))
                                .map(String::trim).filter(s -> !s.isEmpty()).toList() : List.of(),
                        f.get("cover"), false, f.getOrDefault("latest", ""), readable,
                        externalBase.isEmpty() ? null : externalBase + id, sourceId,
                        readable ? id : null));
            }
            return out;
        }
        Document doc = fetchHtml(rule.path("url").asText(), root.path("headers"));
        List<MangaSource.RankItem> parsed = parseRank(doc, rule, sourceId, limit);
        if (parsed.isEmpty()) {
            throw new IllegalStateException("榜单未匹配到条目（上游结构可能变化）：" + rule.path("list").asText());
        }
        return parsed;
    }

    /**
     * 首页发现流分区（M24）：规则 rank.sections 在同一页面内按块选择器拆分多个
     * 推荐/榜单分区；无 sections 时降级为单分区「热门」。同 URL 只抓一次。
     */
    public List<MangaSource.Section> rankSections(String rulesJson, String sourceId, int limit) throws Exception {
        JsonNode root = objectMapper.readTree(rulesJson);
        JsonNode rule = section(root, "rank");
        JsonNode sections = rule.path("sections");
        if (!sections.isArray() || sections.isEmpty()) {
            return List.of(new MangaSource.Section("hot", "热门", rank(rulesJson, sourceId, limit)));
        }
        boolean html = !"api".equals(rule.path("mode").asText("html"));
        JsonNode bootstrap = root.path("bootstrapVersion");
        if (html) {
            List<MangaSource.Section> out = new ArrayList<>();
            boolean anyContent = false;
            Map<String, Document> docs = new java.util.HashMap<>();
            for (JsonNode s : sections) {
                String url = s.path("url").asText(rule.path("url").asText());
                Document doc = docs.get(url);
                if (doc == null) {
                    doc = fetchHtml(url, root.path("headers"));
                    docs.put(url, doc);
                }
                List<MangaSource.RankItem> items = sectionItems(doc, s, rule, sourceId, limit);
                if (!items.isEmpty()) anyContent = true;
                out.add(new MangaSource.Section(s.path("id").asText("hot"),
                        s.path("name").asText(s.path("id").asText("hot")), items));
            }
            if (!anyContent) {
                throw new IllegalStateException("首页分区未匹配到任何条目（上游结构可能变化）");
            }
            return out;
        }
        List<MangaSource.Section> out = new ArrayList<>();
        boolean anyContent = false;
        for (JsonNode s : sections) {
            String id = s.path("id").asText("hot");
            String name = s.path("name").asText(id);
            JsonNode fields = s.path("fields").isMissingNode() ? rule.path("fields") : s.path("fields");
            String url = s.path("url").asText(rule.path("url").asText());
            String listPath = s.path("list").asText(rule.path("list").asText("$[*]"));
            List<MangaSource.RankItem> items = new ArrayList<>();
            for (Map<String, Object> it : fetchApi(url, root.path("headers"), Map.of(), listPath, bootstrap)) {
                if (items.size() >= limit) break;
                Map<String, String> f = apiFields(fields, it);
                String cid = f.getOrDefault("id", "");
                String title = f.getOrDefault("title", "");
                if (cid.isBlank() || title.isBlank()) continue;
                items.add(new MangaSource.RankItem(sourceId, cid, title,
                        List.of(), f.get("cover"), false, f.getOrDefault("latest", ""),
                        true, null, sourceId, cid));
            }
            if (!items.isEmpty()) anyContent = true;
            out.add(new MangaSource.Section(id, name, items));
        }
        if (!anyContent) {
            throw new IllegalStateException("首页分区未匹配到任何条目（上游结构可能变化）");
        }
        return out;
    }

    /** 单分区字段解析（分区可覆盖 fields/list）。 */
    private List<MangaSource.RankItem> sectionItems(Document doc, JsonNode section, JsonNode rule,
                                                    String sourceId, int limit) {
        JsonNode fields = section.path("fields").isMissingNode()
                ? rule.path("fields") : section.path("fields");
        return parseRank(doc, section.path("list").asText(rule.path("list").asText("")),
                fields, sourceId, limit);
    }

    /** HTML 分区解析（包内可见便于测试）：同页内按块选择器拆分，逐区去重。 */
    List<MangaSource.Section> parseRankSections(Document doc, JsonNode rule,
                                                String sourceId, int limit) {
        List<MangaSource.Section> out = new ArrayList<>();
        boolean anyContent = false;
        for (JsonNode s : rule.path("sections")) {
            List<MangaSource.RankItem> items = sectionItems(doc, s, rule, sourceId, limit);
            if (!items.isEmpty()) anyContent = true;
            out.add(new MangaSource.Section(s.path("id").asText("hot"),
                    s.path("name").asText(s.path("id").asText("hot")), items));
        }
        if (!anyContent) {
            throw new IllegalStateException("首页分区未匹配到任何条目（上游结构可能变化）");
        }
        return out;
    }

    /** HTML 榜单解析（包内可见便于测试）：按 id 去重、标题为空（页脚链接）跳过。 */
    List<MangaSource.RankItem> parseRank(Document doc, JsonNode rule, String sourceId, int limit) {
        return parseRank(doc, rule.path("list").asText(), rule.path("fields"), sourceId, limit);
    }

    List<MangaSource.RankItem> parseRank(Document doc, String listSelector, JsonNode fields,
                                         String sourceId, int limit) {
        List<MangaSource.RankItem> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Element el : doc.select(listSelector)) {
            if (out.size() >= limit) break;
            Map<String, String> f = htmlFields(fields, el);
            String id = f.getOrDefault("id", "");
            String title = f.getOrDefault("title", "");
            if (id.isBlank() || title.isBlank() || !seen.add(id)) continue;
            out.add(new MangaSource.RankItem(sourceId, id, title, List.of(),
                    f.get("cover"), false, f.getOrDefault("latest", ""), true, null, sourceId, id));
        }
        return out;
    }

    public List<MangaSource.SearchItem> search(String rulesJson, String sourceId, String query, int limit)
            throws Exception {
        JsonNode root = objectMapper.readTree(rulesJson);
        JsonNode rule = section(root, "search");
        List<MangaSource.SearchItem> out = new ArrayList<>();
        if ("html".equals(rule.path("mode").asText("api"))) {
            Document doc = fetchHtml(rule.path("url").asText()
                    .replace("{query}", encode(query)), root.path("headers"));
            for (Element el : doc.select(rule.path("list").asText())) {
                if (out.size() >= limit) break;
                Map<String, String> f = htmlFields(rule.path("fields"), el);
                String id = f.getOrDefault("id", "");
                if (id.isBlank()) continue;
                out.add(new MangaSource.SearchItem(sourceId, id, f.getOrDefault("title", ""),
                        f.getOrDefault("altTitle", ""), f.get("cover"), f.get("year")));
            }
            return out;
        }
        String listPath = rule.path("list").asText("$[*]");
        for (Map<String, Object> it : fetchApi(rule, root.path("headers"), Map.of("query", query), listPath)) {
            if (out.size() >= limit) break;
            Map<String, String> f = apiFields(rule.path("fields"), it);
            String id = f.getOrDefault("id", "");
            if (id.isBlank()) continue;
            out.add(new MangaSource.SearchItem(sourceId, id, f.getOrDefault("title", ""),
                    f.getOrDefault("altTitle", ""), f.get("cover"), f.get("year")));
        }
        return out;
    }

    // ── 详情 ──

    public MangaSource.Detail detail(String rulesJson, String sourceId, String mangaId) throws Exception {
        JsonNode root = objectMapper.readTree(rulesJson);
        JsonNode rule = section(root, "detail");
        String url = rule.path("url").asText().replace("{id}", mangaId);
        Cached cached = detailCache.get(url);
        String html;
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            html = cached.text();
        } else {
            html = fetchText(url, root.path("headers"));
            if (detailCache.size() > 50) detailCache.clear();
            detailCache.put(url, new Cached(html, Instant.now().plus(Duration.ofMinutes(10))));
        }
        Document doc = Jsoup.parse(html, url);
        return parseDetail(doc, rule, sourceId, mangaId);
    }

    /** HTML 详情解析（包内可见便于测试）。 */
    MangaSource.Detail parseDetail(Document doc, JsonNode rule, String sourceId, String mangaId) {
        Map<String, String> meta = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : rule.path("meta").properties()) {
            String v = extract(doc, e.getValue());
            if (v != null && !v.isBlank()) meta.put(e.getKey(), v);
        }
        String description = meta.getOrDefault("description", "");
        String marker = rule.path("descriptionMarker").asText("");
        if (!marker.isEmpty() && description.contains(marker)) {
            description = description.substring(description.lastIndexOf(marker) + marker.length());
        }

        List<String> authors = List.of();
        JsonNode authorsRule = rule.path("authors");
        if (!authorsRule.isMissingNode()) {
            Element el = doc.selectFirst(authorsRule.path("select").asText());
            if (el != null) {
                authors = Arrays.stream(el.text().split(authorsRule.path("delimiter").asText(" ")))
                        .map(String::trim).filter(s -> !s.isEmpty() && !s.equals("作者：")).toList();
            }
        }

        int chapterPages = rule.path("chapterPages").asInt(0);
        JsonNode ch = rule.path("chapters");
        List<MangaSource.ChapterItem> chapters = new ArrayList<>();
        java.util.Set<String> seenIds = java.util.HashSet.newHashSet(16);
        for (Element el : doc.select(ch.path("list").asText())) {
            String rawId = extract(el, ch.path("id"));
            if (rawId == null || rawId.isBlank()) continue;
            String id = ch.path("id").path("template").asText("{mangaId}-{v}")
                    .replace("{mangaId}", mangaId).replace("{v}", rawId);
            if (!seenIds.add(id)) continue; // 同 cid 出现两次（卡片 + 列表）
            String label = extract(el, ch.path("label"));
            String title = extract(el, ch.path("title"));
            chapters.add(new MangaSource.ChapterItem(id,
                    label == null || label.isBlank() ? "?" : label,
                    title == null ? "" : title, "zh", chapterPages, false));
        }
        if ("numeric-asc".equals(rule.path("chapterSort").asText(""))) {
            chapters.sort(java.util.Comparator.comparingLong(c -> parseLongSafe(c.label())));
        }

        MangaSource.SearchItem item = new MangaSource.SearchItem(sourceId, mangaId,
                meta.getOrDefault("title", ""), authors.isEmpty() ? "" : authors.getFirst(),
                meta.get("cover"), meta.get("year"));
        return new MangaSource.Detail(item, description, meta.getOrDefault("status", ""),
                meta.get("year"), List.of(), chapters);
    }

    // ── 页图 ──

    public MangaSource.Pages pages(String rulesJson, String chapterId) throws Exception {
        JsonNode root = objectMapper.readTree(rulesJson);
        JsonNode rule = section(root, "pages");
        Map<String, String> vars = parseChapterId(rule.path("chapterIdTemplate").asText("{comicId}-{cid}"), chapterId);
        switch (rule.path("mode").asText("api")) {
            case "decode" -> {
                String decoderName = rule.path("decode").asText("");
                MangaPageDecoder decoder = decoders.get(decoderName);
                if (decoder == null) {
                    throw new IllegalStateException("未知解码钩子：" + decoderName);
                }
                return new MangaSource.Pages(decoder.decode(rule, vars), List.of(), false);
            }
            case "html" -> {
                String url = applyVars(rule.path("url").asText(), vars);
                Document doc = fetchHtml(url, root.path("headers"));
                List<String> urls = new ArrayList<>();
                for (Element el : doc.select(rule.path("list").asText())) {
                    String u = extract(el, rule.path("fields").path("page"));
                    if (u != null && !u.isBlank()) urls.add(u);
                }
                if (urls.isEmpty()) throw new IllegalStateException("未解析到页图（上游结构可能变化）");
                return new MangaSource.Pages(urls, List.of(), false);
            }
            default -> {
                String url = applyVars(rule.path("url").asText(), vars);
                List<String> urls = JsonPath.read(fetchText(url, root.path("headers")),
                        rule.path("list").asText("$[*]"));
                if (urls.isEmpty()) throw new IllegalStateException("未解析到页图");
                return new MangaSource.Pages(urls, List.of(), false);
            }
        }
    }

    /** 章节 ID 按模板拆变量："{comicId}-{cid}" 与 "505430-1" → {comicId:505430, cid:1}。 */
    static Map<String, String> parseChapterId(String template, String chapterId) {
        Matcher tm = VAR.matcher(template);
        List<String> names = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        while (tm.find()) {
            names.add(tm.group(1));
            starts.add(tm.start());
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("chapterIdTemplate 缺少变量：" + template);
        }
        Map<String, String> vars = new LinkedHashMap<>();
        int pos = 0;
        for (int i = 0; i < names.size(); i++) {
            int litStart = i == 0 ? 0 : starts.get(i - 1) + names.get(i - 1).length() + 2;
            String literal = template.substring(litStart, starts.get(i));
            if (!literal.isEmpty()) {
                int idx = chapterId.indexOf(literal, pos);
                if (idx < 0) {
                    throw new IllegalArgumentException("章节 ID 不符合模板：" + chapterId);
                }
                if (i > 0) vars.put(names.get(i - 1), chapterId.substring(pos, idx));
                pos = idx + literal.length();
            }
        }
        vars.put(names.getLast(), chapterId.substring(pos));
        for (String v : vars.values()) {
            if (v.isEmpty()) throw new IllegalArgumentException("章节 ID 不符合模板：" + chapterId);
        }
        return vars;
    }

    // ── 字段提取 ──

    private Map<String, String> htmlFields(JsonNode fields, Element el) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : fields.properties()) {
            String v = extract(el, e.getValue());
            if (v != null) out.put(e.getKey(), v);
        }
        return out;
    }

    private Map<String, String> apiFields(JsonNode fields, Map<String, Object> item) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : fields.properties()) {
            try {
                Object v = JsonPath.read(item, e.getValue().asText());
                if (v != null) out.put(e.getKey(), String.valueOf(v));
            } catch (Exception ignored) {
                // 单字段缺失不阻断整条（如封面/年份）
            }
        }
        return out;
    }

    /** HTML 字段：{select?, attr?, regex?}，无 attr 取 text；regex 取第一个捕获组。 */
    private String extract(Element scope, JsonNode field) {
        if (field == null || field.isMissingNode()) return null;
        String select = field.path("select").asText("");
        Element el = select.isEmpty() ? scope : scope.selectFirst(select);
        if (el == null) return null;
        String attr = field.path("attr").asText("");
        String v = attr.isEmpty() ? el.text() : el.attr(attr);
        if (v == null) return null;
        String regex = field.path("regex").asText("");
        if (!regex.isEmpty()) {
            Matcher m = Pattern.compile(regex).matcher(v);
            if (!m.find()) return null;
            v = m.groupCount() >= 1 ? m.group(1) : m.group();
        }
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String applyVars(String template, Map<String, String> vars) {
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.replaceAll("[^0-9-]", ""));
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private JsonNode section(JsonNode root, String name) {
        JsonNode s = root.path(name);
        if (s.isMissingNode()) {
            throw new IllegalStateException("规则缺少 " + name + " 节");
        }
        return s;
    }

    // ── HTTP ──

    private List<Map<String, Object>> fetchApi(JsonNode rule, JsonNode headers,
                                               Map<String, String> vars, String listPath) throws Exception {
        return fetchApi(rule.path("url").asText(), headers, vars, listPath);
    }

    private List<Map<String, Object>> fetchApi(String url, JsonNode headers,
                                               Map<String, String> vars, String listPath) throws Exception {
        return fetchApi(url, headers, vars, listPath, null);
    }

    private List<Map<String, Object>> fetchApi(String url, JsonNode headers,
                                               Map<String, String> vars, String listPath,
                                               JsonNode bootstrap) throws Exception {
        return JsonPath.read(fetchText(applyVars(url, vars), headers, bootstrap), listPath);
    }

    private String fetchText(String url, JsonNode headers) throws Exception {
        return fetchText(url, headers, null);
    }

    /**
     * GET 上游。bootstrap 为非 null 时先做「版本自举」（拷贝漫画风控要求请求头
     * 携带最新版本号，见 V17）：GET 自举 URL → JSONPath 取版本 → 注入 header。
     */
    private String fetchText(String url, JsonNode headers, JsonNode bootstrap) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT).GET();
        if (headers != null) {
            for (Map.Entry<String, JsonNode> e : headers.properties()) {
                if (e.getValue().isTextual()) b.header(e.getKey(), e.getValue().asText());
            }
        }
        if (bootstrap != null && !bootstrap.isMissingNode()) {
            String version = resolveBootstrapVersion(bootstrap);
            String header = bootstrap.path("header").asText("version");
            if (!version.isBlank()) {
                b.header(header, version);
                for (JsonNode also : bootstrap.path("also")) {
                    if (also.isTextual()) b.header(also.asText(), version);
                }
            }
        }
        HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("上游 HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /** 自举版本号：GET bootstrap.url → JSONPath 取版本，缓存 1 小时。 */
    private static final ConcurrentHashMap<String, CachedBootstrap> BOOTSTRAP_CACHE = new ConcurrentHashMap<>();

    private record CachedBootstrap(String version, Instant expiresAt) {}

    private String resolveBootstrapVersion(JsonNode bootstrap) throws Exception {
        String url = bootstrap.path("url").asText();
        String path = bootstrap.path("path").asText("$.version");
        CachedBootstrap cached = BOOTSTRAP_CACHE.get(url);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.version();
        }
        String body = fetchText(url, null, null);
        String version;
        try {
            Object v = JsonPath.read(body, path);
            version = v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            throw new IllegalStateException("版本自举失败（路径无匹配）：" + path);
        }
        if (version.isBlank()) {
            throw new IllegalStateException("版本自举结果为空");
        }
        BOOTSTRAP_CACHE.put(url, new CachedBootstrap(version, Instant.now().plus(Duration.ofHours(1))));
        return version;
    }

    private Document fetchHtml(String url, JsonNode headers) throws Exception {
        return Jsoup.parse(fetchText(url, headers), url);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
