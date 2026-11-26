package com.mangashelf.external.manga;

import com.mangashelf.external.TagNormalizer;
import com.mangashelf.ingestion.Job;
import com.mangashelf.ingestion.JobContext;
import com.mangashelf.ingestion.JobHandler;
import com.mangashelf.ingestion.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * manga.catalog.sync：漫画目录同步（manga-catalog-sync）。
 * params.sourceId 指定单源（逐源任务，失败隔离）：
 * - tencent：遍历腾讯动漫 15 个分类页 × 前 6 页热度榜（提取完整多标签、作者、最新话与封面）+ 榜单/分区补充
 * - copymanga：遍历题材 × 前 3 页分页抓取（复用 copymanga 代理配置，单题材失败隔离）
 * - bilibili / 规则源：rank/sections + 题材搜索可见条目入库（渐进式）
 * 重复同步幂等（(source, source_item_id) upsert，多题材标签做并集累加）。
 */
@Component
public class MangaCatalogSyncHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(MangaCatalogSyncHandler.class);
    private static final String COPY_API = "https://api.manga2026.xyz/api/v3";
    private static final String TENCENT_THEME_URL = "https://ac.qq.com/Comic/all/theme/%d/search/hot/page/%d";
    private static final String WEB_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    /** 题材间/分页间限速（毫秒）。 */
    private static final long GENRE_SLEEP_MS = 1200;
    private static final long PAGE_SLEEP_MS = 400;
    private static final long TENCENT_PAGE_SLEEP_MS = 250;
    private static final int PAGE_SIZE = 60;
    private static final int COPY_MAX_PAGES_PER_GENRE = 3;
    private static final int TENCENT_MAX_PAGES_PER_THEME = 6;

    record TencentTheme(int id, String name) {}

    static final List<TencentTheme> TENCENT_THEMES = List.of(
            new TencentTheme(101, "玄幻"),
            new TencentTheme(102, "奇幻"),
            new TencentTheme(103, "异能"),
            new TencentTheme(104, "冒险"),
            new TencentTheme(105, "恋爱"),
            new TencentTheme(106, "剧情"),
            new TencentTheme(108, "科幻"),
            new TencentTheme(109, "动作"),
            new TencentTheme(110, "恐怖"),
            new TencentTheme(111, "犯罪"),
            new TencentTheme(112, "悬疑"),
            new TencentTheme(113, "日常"),
            new TencentTheme(114, "竞技"),
            new TencentTheme(115, "武侠"),
            new TencentTheme(116, "历史")
    );

    private static final Pattern TENCENT_ITEM_LI = Pattern.compile(
            "<li\\s+class=\"ret-search-item[^\"]*\"[^>]*>([\\s\\S]*?)</li>");
    private static final Pattern TENCENT_ID = Pattern.compile("/Comic/comicInfo/id/(\\d+)");
    private static final Pattern TENCENT_COVER = Pattern.compile("data-original=\"(https?://[^\"]+)\"");
    private static final Pattern TENCENT_TITLE_H3 = Pattern.compile(
            "<h3[^>]*class=\"[^\"]*ret-works-title[^\"]*\"[^>]*>[\\s\\S]*?<a[^>]*title=\"([^\"]+)\"");
    private static final Pattern TENCENT_TITLE_FALLBACK = Pattern.compile(
            "<a[^>]*href=\"/Comic/comicInfo/id/\\d+\"[^>]*title=\"([^\"]+)\"");
    private static final Pattern TENCENT_REMARKS = Pattern.compile(
            "<span[^>]*class=\"[^\"]*mod-cover-list-text[^\"]*\"[^>]*>([^<]+)</span>");
    private static final Pattern TENCENT_AUTHOR = Pattern.compile(
            "<p[^>]*class=\"[^\"]*ret-works-author[^\"]*\"[^>]*title=\"([^\"]+)\"");
    private static final Pattern TENCENT_TAGS_BLOCK = Pattern.compile(
            "<p[^>]*class=\"[^\"]*ret-works-tags[^\"]*\"[^>]*>([\\s\\S]*?)</p>");
    private static final Pattern HTML_SPAN = Pattern.compile("<span[^>]*>([^<]+)</span>");

    record ParsedTencentItem(String id, String title, String author, String coverUrl,
                             String remarks, List<String> tags) {}

    private final MangaSourceRegistry registry;
    private final MangaCatalogRepository catalog;
    private final ObjectMapper objectMapper;
    private final String copyMangaProxy;
    private final Sleeper sleeper;

    /** 可注入的休眠（测试跳过真实等待）。 */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Autowired
    public MangaCatalogSyncHandler(MangaSourceRegistry registry, MangaCatalogRepository catalog,
                                   ObjectMapper objectMapper,
                                   @Value("${mangashelf.copymanga.proxy:}") String copyMangaProxy) {
        this(registry, catalog, objectMapper, copyMangaProxy, Thread::sleep);
    }

    MangaCatalogSyncHandler(MangaSourceRegistry registry, MangaCatalogRepository catalog,
                            ObjectMapper objectMapper, Sleeper sleeper) {
        this(registry, catalog, objectMapper, "", sleeper);
    }

    MangaCatalogSyncHandler(MangaSourceRegistry registry, MangaCatalogRepository catalog,
                            ObjectMapper objectMapper, String copyMangaProxy, Sleeper sleeper) {
        this.registry = registry;
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.copyMangaProxy = copyMangaProxy == null ? "" : copyMangaProxy.trim();
        this.sleeper = sleeper;
    }

    @Override
    public String kind() {
        return Job.KIND_MANGA_CATALOG_SYNC;
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        Job job = ctx.job();
        String sourceId = sourceIdOf(job.params());
        if (sourceId == null || sourceId.isBlank()) {
            return JobResult.failure("SOURCE_REQUIRED", "params 缺少 sourceId");
        }
        MangaSource source = registry.find(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("未注册的漫画源：" + sourceId));

        int saved = switch (sourceId) {
            case "tencent" -> syncTencent(ctx, source);
            case "copymanga" -> syncCopyManga(ctx, source);
            default -> syncProgressive(ctx, source);
        };
        log.info("manga catalog sync {}: {} items", sourceId, saved);
        return JobResult.success("synced:" + saved);
    }

    private String sourceIdOf(String paramsJson) {
        try {
            JsonNode params = objectMapper.readTree(paramsJson == null ? "{}" : paramsJson);
            return params.path("sourceId").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 腾讯动漫：遍历 15 个分类页 × 前 6 页热度榜（提取完整多标签），再补充榜单与分区。 */
    private int syncTencent(JobContext ctx, MangaSource source) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        int saved = 0;
        for (int ti = 0; ti < TENCENT_THEMES.size(); ti++) {
            TencentTheme theme = TENCENT_THEMES.get(ti);
            ctx.progress().emit("sync", "腾讯分类 " + theme.name() + "（" + (ti + 1) + "/" + TENCENT_THEMES.size() + "）");
            try {
                for (int page = 1; page <= TENCENT_MAX_PAGES_PER_THEME; page++) {
                    String url = String.format(TENCENT_THEME_URL, theme.id(), page);
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(15))
                            .header("User-Agent", WEB_UA)
                            .header("Accept", "text/html,application/xhtml+xml")
                            .header("Referer", "https://ac.qq.com/Comic/all")
                            .GET().build();
                    HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() != 200) {
                        log.warn("tencent theme {} page {} HTTP {}", theme.name(), page, resp.statusCode());
                        break;
                    }
                    List<ParsedTencentItem> items = parseTencentListPage(resp.body(), theme.name());
                    if (items.isEmpty()) {
                        break;
                    }
                    for (ParsedTencentItem item : items) {
                        catalog.upsert(UUID.randomUUID(), "tencent", item.id(), item.title(),
                                item.author(), item.coverUrl(), item.remarks(), item.tags(), null);
                        saved++;
                    }
                    sleeper.sleep(TENCENT_PAGE_SLEEP_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                log.warn("tencent theme {} sync failed: {}", theme.name(), e.getMessage());
            }
        }
        saved += syncProgressive(ctx, source);
        return saved;
    }

    /** 解析腾讯动漫分类列表页 HTML，提取单页全部条目与多标签。 */
    static List<ParsedTencentItem> parseTencentListPage(String html, String themeName) {
        if (html == null || html.isBlank()) return List.of();
        List<ParsedTencentItem> out = new ArrayList<>();
        Matcher li = TENCENT_ITEM_LI.matcher(html);
        while (li.find()) {
            String block = li.group(1);
            String id = firstGroup(TENCENT_ID, block);
            String title = firstGroup(TENCENT_TITLE_H3, block);
            if (title.isBlank()) {
                title = firstGroup(TENCENT_TITLE_FALLBACK, block);
            }
            if (id.isBlank() || title.isBlank()) continue;
            title = unescapeHtml(title).trim();
            String cover = firstGroup(TENCENT_COVER, block).trim();
            String remarks = unescapeHtml(firstGroup(TENCENT_REMARKS, block)).trim();
            String author = unescapeHtml(firstGroup(TENCENT_AUTHOR, block)).trim();

            LinkedHashSet<String> rawTags = new LinkedHashSet<>();
            if (themeName != null && !themeName.isBlank()) {
                rawTags.add(themeName.trim());
            }
            String tagsBlock = firstGroup(TENCENT_TAGS_BLOCK, block);
            if (!tagsBlock.isBlank()) {
                Matcher sp = HTML_SPAN.matcher(tagsBlock);
                while (sp.find()) {
                    String tagText = unescapeHtml(sp.group(1)).trim();
                    if (!tagText.isBlank() && !tagText.contains("人气")) {
                        rawTags.add(tagText);
                    }
                }
            }
            List<String> cleanTags = TagNormalizer.normalize(rawTags);
            out.add(new ParsedTencentItem(id, title, author, cover, remarks, cleanTags));
        }
        return out;
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private static String unescapeHtml(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ");
    }

    /** 拷贝漫画：逐题材分页（有界页数 + 代理复用 + 单题材失败隔离）。 */
    private int syncCopyManga(JobContext ctx, MangaSource source) throws Exception {
        List<String> genres = source.genres();
        if (genres.isEmpty()) {
            ctx.progress().emit("sync", "源无题材清单，跳过全量同步");
            return 0;
        }
        HttpClient client = createCopyClient(copyMangaProxy);
        int saved = 0;
        int failedGenres = 0;
        int maxOffset = PAGE_SIZE * COPY_MAX_PAGES_PER_GENRE;
        for (int gi = 0; gi < genres.size(); gi++) {
            String genre = genres.get(gi);
            ctx.progress().emit("sync", "拷贝题材 " + genre + "（" + (gi + 1) + "/" + genres.size() + "）");
            try {
                int offset = 0;
                while (offset < maxOffset) {
                    JsonNode list = fetchCopyFilter(client, genre, offset);
                    int count = 0;
                    for (JsonNode item : list) {
                        String id = item.path("path_word").asText("");
                        String name = item.path("name").asText("");
                        if (id.isBlank() || name.isBlank()) continue;
                        List<String> tags = new ArrayList<>();
                        tags.add(genre);
                        item.path("theme").forEach(t -> {
                            String tag = t.path("name").asText("").trim();
                            if (!tag.isBlank()) tags.add(tag);
                        });
                        String cover = item.path("cover").asText("").replaceFirst("\\.\\d+x\\d+\\.jpg$", "");
                        catalog.upsert(UUID.randomUUID(), "copymanga", id, name, "",
                                cover, item.path("status").path("display").asText(""),
                                tags, null);
                        saved++;
                        count++;
                    }
                    if (count == 0) break;
                    offset += count;
                    sleeper.sleep(PAGE_SLEEP_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                failedGenres++;
                log.warn("copymanga genre {} sync failed: {}", genre, e.getMessage());
            }
            sleeper.sleep(GENRE_SLEEP_MS);
        }
        if (saved == 0 && failedGenres == genres.size()) {
            // 回退至少尝试拉榜单，避免零入库
            saved += syncProgressive(ctx, source);
        }
        return saved;
    }

    static HttpClient createCopyClient(String proxy) {
        ProxySelector selector = HttpClient.Builder.NO_PROXY;
        if (proxy != null && !proxy.isBlank()) {
            try {
                URI target = URI.create(proxy.trim());
                if ("http".equals(target.getScheme()) && target.getHost() != null
                        && target.getPort() > 0 && target.getPort() <= 65535) {
                    selector = ProxySelector.of(new InetSocketAddress(target.getHost(), target.getPort()));
                }
            } catch (Exception e) {
                log.warn("ignoring invalid copymanga proxy {}: {}", proxy, e.getMessage());
            }
        }
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(selector)
                .build();
    }

    private JsonNode fetchCopyFilter(HttpClient client, String genre, int offset) throws Exception {
        String url = COPY_API + "/h5/filter/list?theme="
                + URLEncoder.encode(genre, StandardCharsets.UTF_8)
                + "&offset=" + offset + "&limit=" + PAGE_SIZE
                + "&group=0&region=0&ordering=-datetime_updated";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "COPY/3.0.9")
                .header("Accept", "application/json")
                .header("version", "3.0.9")
                .header("versionCode", "83")
                .header("platform", "3")
                .header("webp", "1")
                .GET().build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("copymanga filter HTTP " + resp.statusCode());
        }
        return objectMapper.readTree(resp.body()).path("results").path("list");
    }

    /** 渐进式：rank/sections + 题材页可见条目入库（哔哩/腾讯/规则源通用路径）。 */
    private int syncProgressive(JobContext ctx, MangaSource source) throws Exception {
        int saved = 0;
        try {
            for (MangaSource.RankItem item : source.rank(100)) {
                saved += upsertRankItem(source, item);
            }
        } catch (Exception e) {
            ctx.progress().emit("sync", "榜单抓取失败：" + e.getMessage());
            log.warn("catalog progressive sync {} rank failed: {}", source.id(), e.getMessage());
        }
        try {
            for (MangaSource.Section section : source.sections(20)) {
                for (MangaSource.RankItem item : section.items()) {
                    saved += upsertRankItem(source, item);
                }
            }
        } catch (Exception e) {
            ctx.progress().emit("sync", "分区抓取失败：" + e.getMessage());
        }
        if (!"tencent".equals(source.id())
                && source.capabilities().contains(MangaSource.Capability.SEARCH)
                && !source.genres().isEmpty()) {
            List<String> genres = source.genres().stream().limit(12).toList();
            for (String genre : genres) {
                try {
                    for (MangaSource.SearchItem item : source.searchByGenre(genre, "zh", 24)) {
                        if (item.id() == null || item.id().isBlank() || item.title() == null || item.title().isBlank()) {
                            continue;
                        }
                        catalog.upsert(UUID.randomUUID(), source.id(), item.id(), item.title(),
                                item.altTitle() == null ? "" : item.altTitle(),
                                item.coverUrl() == null ? "" : item.coverUrl(),
                                "", List.of(genre), null);
                        saved++;
                    }
                    sleeper.sleep(PAGE_SLEEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    log.debug("catalog sync {} genre {} failed: {}", source.id(), genre, e.getMessage());
                }
            }
        }
        return saved;
    }

    private int upsertRankItem(MangaSource source, MangaSource.RankItem item) {
        if (item.id() == null || item.id().isBlank() || item.title() == null || item.title().isBlank()) {
            return 0;
        }
        String remarks = item.latestLabel() != null && !item.latestLabel().isBlank()
                ? item.latestLabel().trim()
                : (item.finished() ? "已完结" : "");
        catalog.upsert(UUID.randomUUID(), source.id(), item.id(), item.title(),
                item.authors() == null ? "" : String.join(" / ", item.authors()),
                item.coverUrl() == null ? "" : item.coverUrl(), remarks, List.of(), null);
        return 1;
    }
}
