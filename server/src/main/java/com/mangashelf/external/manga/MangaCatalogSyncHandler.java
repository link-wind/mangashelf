package com.mangashelf.external.manga;

import com.mangashelf.external.CatalogRanking;
import com.mangashelf.external.CatalogRankingService;
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
 * params.sourceId 指定单源（逐源任务，失败隔离），可选 params.maxPages 控制深度：
 * - copymanga：走 /api/v3/comics（拼音 theme slug × 热度分页），目标 ~4,000~5,000 部热门日漫/韩漫
 * - bilibili：走 BilibiliHttpClient.classPage（全站热度前 80 页 + 15 官方题材前 3 页），目标 ~2,000~2,500 部
 * - tencent：15 分类前 10 页 + 全站热度前 150 页（自带完整多标签），目标 ~2,200~2,600 部国漫
 * - 其他规则源：rank/sections + 题材搜索渐进式入库
 * 重复同步幂等（(source, source_item_id) upsert，多题材标签做并集累加，同时写入繁简归一化 title_key）。
 */
@Component
public class MangaCatalogSyncHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(MangaCatalogSyncHandler.class);
    private static final String COPY_API_PRIMARY = "https://api.manga2026.xyz/api/v3";
    private static final String COPY_API_FALLBACK = "https://api.copy202602.com/api/v3";
    private static final String TENCENT_THEME_URL = "https://ac.qq.com/Comic/all/theme/%d/search/hot/page/%d";
    private static final String TENCENT_ALL_HOT_URL = "https://ac.qq.com/Comic/all/search/hot/page/%d";
    private static final String WEB_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private static final long COPY_GENRE_SLEEP_MS = 600;
    private static final long COPY_PAGE_SLEEP_MS = 350;
    private static final long BILI_PAGE_SLEEP_MS = 200;
    private static final long TENCENT_PAGE_SLEEP_MS = 200;
    private static final long GENERIC_PAGE_SLEEP_MS = 400;

    private static final int COPY_PAGE_SIZE = 50;
    private static final int COPY_DEFAULT_PAGES_PER_THEME = 6;
    private static final int BILI_PAGE_SIZE = 30;
    private static final int BILI_DEFAULT_HOT_PAGES = 80;
    private static final int BILI_STYLE_PAGES = 3;
    private static final int TENCENT_THEME_PAGES = 10;
    private static final int TENCENT_DEFAULT_HOT_PAGES = 150;

    record CopyTheme(String slug, String tag) {}
    record BiliStyle(int id, String name) {}
    record TencentTheme(int id, String name) {}

    static final List<CopyTheme> COPY_THEMES = List.of(
            new CopyTheme("aiqing", "恋爱"),
            new CopyTheme("huanlexiang", "搞笑"),
            new CopyTheme("maoxian", "冒险"),
            new CopyTheme("qihuan", "奇幻"),
            new CopyTheme("xiaoyuan", "校园"),
            new CopyTheme("kehuan", "科幻"),
            new CopyTheme("gedou", "动作"),
            new CopyTheme("xuanyi", "悬疑"),
            new CopyTheme("zhiyu", "治愈"),
            new CopyTheme("shenghuo", "日常"),
            new CopyTheme("zhichang", "职场"),
            new CopyTheme("shengui", "恐怖"),
            new CopyTheme("baihe", "百合"),
            new CopyTheme("danmei", "纯爱"),
            new CopyTheme("rexue", "热血"),
            new CopyTheme("hougong", "后宫"),
            new CopyTheme("jizhan", "机甲"),
            new CopyTheme("lishi", "历史")
    );

    static final List<BiliStyle> BILI_STYLES = List.of(
            new BiliStyle(999, "热血"),
            new BiliStyle(997, "古风"),
            new BiliStyle(1016, "玄幻"),
            new BiliStyle(998, "奇幻"),
            new BiliStyle(1023, "悬疑"),
            new BiliStyle(1002, "都市"),
            new BiliStyle(1096, "历史"),
            new BiliStyle(1092, "武侠"),
            new BiliStyle(1088, "竞技"),
            new BiliStyle(1081, "恐怖"),
            new BiliStyle(1063, "架空"),
            new BiliStyle(1060, "青春"),
            new BiliStyle(1054, "奇幻"),
            new BiliStyle(1048, "现代"),
            new BiliStyle(1015, "科幻")
    );

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
    private final BilibiliHttpClient bilibiliClient;
    private final String copyMangaProxy;
    private final Sleeper sleeper;
    private final CatalogRankingService rankingService;

    /** 可注入的休眠（测试跳过真实等待）。 */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Autowired
    public MangaCatalogSyncHandler(MangaSourceRegistry registry, MangaCatalogRepository catalog,
                                   ObjectMapper objectMapper, BilibiliHttpClient bilibiliClient,
                                   @Value("${mangashelf.copymanga.proxy:}") String copyMangaProxy,
                                   CatalogRankingService rankingService) {
        this(registry, catalog, objectMapper, bilibiliClient, copyMangaProxy, Thread::sleep, rankingService);
    }

    MangaCatalogSyncHandler(MangaSourceRegistry registry, MangaCatalogRepository catalog,
                            ObjectMapper objectMapper, Sleeper sleeper) {
        this(registry, catalog, objectMapper, null, "", sleeper, null);
    }

    MangaCatalogSyncHandler(MangaSourceRegistry registry, MangaCatalogRepository catalog,
                            ObjectMapper objectMapper, BilibiliHttpClient bilibiliClient,
                            String copyMangaProxy, Sleeper sleeper) {
        this(registry, catalog, objectMapper, bilibiliClient, copyMangaProxy, sleeper, null);
    }

    MangaCatalogSyncHandler(MangaSourceRegistry registry, MangaCatalogRepository catalog,
                            ObjectMapper objectMapper, BilibiliHttpClient bilibiliClient,
                            String copyMangaProxy, Sleeper sleeper,
                            CatalogRankingService rankingService) {
        this.registry = registry;
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.bilibiliClient = bilibiliClient;
        this.copyMangaProxy = copyMangaProxy == null ? "" : copyMangaProxy.trim();
        this.sleeper = sleeper;
        this.rankingService = rankingService;
    }

    @Override
    public String kind() {
        return Job.KIND_MANGA_CATALOG_SYNC;
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        Job job = ctx.job();
        JsonNode params = parseParams(job.params());
        String sourceId = params.path("sourceId").asText(null);
        if (sourceId == null || sourceId.isBlank()) {
            return JobResult.failure("SOURCE_REQUIRED", "params 缺少 sourceId");
        }
        Integer maxPages = params.has("maxPages") && params.path("maxPages").isInt()
                ? params.path("maxPages").asInt() : null;

        int saved = switch (sourceId) {
            case "bilibili" -> syncBilibili(ctx, maxPages);
            case "copymanga" -> syncCopyManga(ctx, maxPages);
            case "tencent" -> syncTencent(ctx, registry.find("tencent").orElse(null), maxPages);
            default -> {
                MangaSource source = registry.find(sourceId)
                        .orElseThrow(() -> new IllegalArgumentException("未注册的漫画源：" + sourceId));
                yield syncProgressive(ctx, source);
            }
        };
        if (rankingService != null) {
            try {
                rankingService.recomputeMangaScores();
            } catch (Exception e) {
                log.warn("recompute manga scores after sync failed: {}", e.getMessage());
            }
        }
        log.info("manga catalog sync {}: {} items", sourceId, saved);
        return JobResult.success("synced:" + saved);
    }

    private JsonNode parseParams(String paramsJson) {
        try {
            return objectMapper.readTree(paramsJson == null || paramsJson.isBlank() ? "{}" : paramsJson);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    // ── 拷贝漫画（/api/v3/comics + 拼音 theme slug × 热度分页）──

    private int syncCopyManga(JobContext ctx, Integer maxPagesOverride) throws Exception {
        int pagesPerTheme = maxPagesOverride != null
                ? Math.min(Math.max(maxPagesOverride, 1), 20)
                : COPY_DEFAULT_PAGES_PER_THEME;
        HttpClient client = createCopyClient(copyMangaProxy);
        int saved = 0;
        int failedThemes = 0;
        for (int ti = 0; ti < COPY_THEMES.size(); ti++) {
            CopyTheme theme = COPY_THEMES.get(ti);
            ctx.progress().emit("sync", "拷贝题材 " + theme.tag() + "（" + (ti + 1) + "/" + COPY_THEMES.size() + "）");
            try {
                for (int page = 0; page < pagesPerTheme; page++) {
                    int offset = page * COPY_PAGE_SIZE;
                    JsonNode list = fetchCopyComics(client, theme.slug(), offset);
                    int count = 0;
                    for (JsonNode item : list) {
                        String id = item.path("path_word").asText("");
                        String name = item.path("name").asText("");
                        if (id.isBlank() || name.isBlank()) continue;
                        List<String> tags = new ArrayList<>();
                        tags.add(theme.tag());
                        item.path("theme").forEach(t -> {
                            String tag = t.path("name").asText("").trim();
                            if (!tag.isBlank()) tags.add(tag);
                        });
                        String author = joinNames(item.path("author"));
                        String cover = item.path("cover").asText("").replaceFirst("\\.\\d+x\\d+\\.jpg$", "");
                        String remarks = item.path("status").path("display").asText("");
                        CatalogRanking.RegionInfo region = CatalogRanking.fromCopyMangaItem(item, name, tags);
                        double popScore = CatalogRanking.popularityFromCopyPopular(item.path("popular").asLong(0));
                        if (popScore <= 0.0) {
                            popScore = CatalogRanking.popularityFromRank(offset + count, pagesPerTheme * COPY_PAGE_SIZE);
                        }
                        catalog.upsert(UUID.randomUUID(), "copymanga", id, name, author, cover, remarks, tags, null,
                                region.region(), region.confidence(), popScore);
                        saved++;
                        count++;
                    }
                    if (count < COPY_PAGE_SIZE) break;
                    sleeper.sleep(COPY_PAGE_SLEEP_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                failedThemes++;
                log.warn("copymanga theme {}({}) sync failed: {}", theme.tag(), theme.slug(), e.getMessage());
            }
            sleeper.sleep(COPY_GENRE_SLEEP_MS);
        }
        if (saved == 0 && failedThemes == COPY_THEMES.size()) {
            MangaSource copy = registry.find("copymanga").orElse(null);
            if (copy != null) {
                saved += syncProgressive(ctx, copy);
            }
        }
        return saved;
    }

    private JsonNode fetchCopyComics(HttpClient client, String themeSlug, int offset) throws Exception {
        String query = "/comics?theme=" + URLEncoder.encode(themeSlug, StandardCharsets.UTF_8)
                + "&offset=" + offset + "&limit=" + COPY_PAGE_SIZE + "&ordering=-popular";
        Exception lastErr = null;
        for (String base : List.of(COPY_API_PRIMARY, COPY_API_FALLBACK)) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(base + query))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "COPY/3.0.9")
                        .header("Accept", "application/json")
                        .header("version", "3.0.9")
                        .header("versionCode", "83")
                        .header("platform", "3")
                        .header("region", "1")
                        .header("webp", "1")
                        .GET().build();
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new IllegalStateException("copymanga comics HTTP " + resp.statusCode());
                }
                JsonNode root = objectMapper.readTree(resp.body());
                return root.path("results").path("list");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                lastErr = e;
            }
        }
        throw lastErr == null ? new IllegalStateException("copymanga comics failed") : lastErr;
    }

    private static String joinNames(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) return "";
        List<String> names = new ArrayList<>();
        for (JsonNode n : arr) {
            String s = n.isTextual() ? n.asText("") : n.path("name").asText("");
            if (!s.isBlank()) names.add(s.trim());
        }
        return String.join(" / ", names);
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

    // ── 哔哩哔哩漫画（ClassPage 分地区官方热度 + 官方 15 题材）──

    static final int[] BILI_AREAS = {2, 1, 6, 5}; // 2=日本, 1=大陆, 6=韩国, 5=其他

    private int syncBilibili(JobContext ctx, Integer maxPagesOverride) throws Exception {
        if (bilibiliClient == null) {
            throw new IllegalStateException("BilibiliHttpClient 未初始化");
        }
        int hotPages = maxPagesOverride != null
                ? Math.min(Math.max(maxPagesOverride, 1), 150)
                : BILI_DEFAULT_HOT_PAGES;
        int pagesPerArea = Math.max(10, hotPages / 2);
        int saved = 0;

        // 阶段 1：按官方 area_id 分地区抓取（每条精确获得 JP/CN/KR/OVERSEAS 置信度 30 与热度排位分）
        for (int areaId : BILI_AREAS) {
            CatalogRanking.RegionInfo areaReg = CatalogRanking.fromBilibiliAreaId(areaId);
            int limitPages = areaId == 5 ? Math.min(pagesPerArea, 15) : pagesPerArea;
            for (int page = 1; page <= limitPages; page++) {
                if (page == 1 || page % 10 == 0) {
                    ctx.progress().emit("sync", "哔哩地区 " + areaReg.region() + " 页 " + page + "/" + limitPages);
                }
                try {
                    JsonNode list = bilibiliClient.classPage(-1, areaId, page, BILI_PAGE_SIZE);
                    int baseRank = (page - 1) * BILI_PAGE_SIZE;
                    int count = upsertBiliPage(list, null, areaReg, baseRank, limitPages * BILI_PAGE_SIZE);
                    saved += count;
                    if (count < BILI_PAGE_SIZE) break;
                    sleeper.sleep(BILI_PAGE_SLEEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    log.warn("bilibili area {} page {} failed: {}", areaId, page, e.getMessage());
                    break;
                }
            }
        }

        // 阶段 2：15 个官方题材各补前 3 页，确保细分题材覆盖充足
        for (int si = 0; si < BILI_STYLES.size(); si++) {
            BiliStyle style = BILI_STYLES.get(si);
            ctx.progress().emit("sync", "哔哩题材 " + style.name() + "（" + (si + 1) + "/" + BILI_STYLES.size() + "）");
            try {
                for (int page = 1; page <= BILI_STYLE_PAGES; page++) {
                    JsonNode list = bilibiliClient.classPage(style.id(), -1, page, BILI_PAGE_SIZE);
                    int count = upsertBiliPage(list, style.name(), null, -1, 0);
                    saved += count;
                    if (count < BILI_PAGE_SIZE) break;
                    sleeper.sleep(BILI_PAGE_SLEEP_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                log.warn("bilibili style {}({}) failed: {}", style.name(), style.id(), e.getMessage());
            }
        }
        return saved;
    }

    private int upsertBiliPage(JsonNode list, String extraStyleTag,
                               CatalogRanking.RegionInfo areaRegion, int baseRank, int horizon) {
        if (list == null || !list.isArray()) return 0;
        int count = 0;
        for (JsonNode c : list) {
            String id = c.path("season_id").asText("");
            String title = c.path("title").asText("").trim();
            if (id.isBlank() || title.isBlank()) continue;
            String cover = c.path("vertical_cover").asText("");
            if (cover.contains("hdslb.com") && !cover.contains("@")) {
                cover = cover + "@480w.jpg";
            }
            boolean finished = c.path("is_finish").asInt(0) == 1;
            int total = c.path("total").asInt(0);
            int lastOrd = c.path("last_ord").asInt(0);
            Integer epCount = total > 0 ? total : (lastOrd > 0 ? lastOrd : null);
            String lastShort = c.path("last_short_title").asText("").trim();
            String remarks = finished
                    ? (epCount != null ? "已完结·全" + epCount + "话" : "已完结")
                    : (!lastShort.isBlank() ? "更新至" + lastShort + "话" : (epCount != null ? "更新至" + epCount + "话" : ""));

            List<String> tags = new ArrayList<>();
            if (extraStyleTag != null && !extraStyleTag.isBlank()) {
                tags.add(extraStyleTag);
            }
            c.path("styles").forEach(s -> {
                String t = s.asText("").trim();
                if (!t.isBlank()) tags.add(t);
            });
            c.path("bottom_info_v2").forEach(s -> {
                String t = s.asText("").trim();
                if (!t.isBlank()) tags.add(t);
            });
            String author = joinNames(c.path("author"));
            CatalogRanking.RegionInfo reg = areaRegion != null
                    ? areaRegion
                    : CatalogRanking.RegionInfo.of(CatalogRanking.REGION_UNKNOWN, CatalogRanking.CONF_NONE);
            double popScore = baseRank >= 0 ? CatalogRanking.popularityFromRank(baseRank + count, horizon) : 0.0;
            catalog.upsert(UUID.randomUUID(), "bilibili", id, title, author, cover, remarks, tags, epCount,
                    reg.region(), reg.confidence(), popScore);
            count++;
        }
        return count;
    }

    // ── 腾讯动漫（15 分类前 10 页 + 全站热度总榜前 150 页）──

    private int syncTencent(JobContext ctx, MangaSource source, Integer maxPagesOverride) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        int saved = 0;

        // 阶段 1：15 分类页（确保细分题材不遗漏）
        int themePages = maxPagesOverride != null
                ? Math.min(Math.max(maxPagesOverride, 1), 20)
                : TENCENT_THEME_PAGES;
        for (int ti = 0; ti < TENCENT_THEMES.size(); ti++) {
            TencentTheme theme = TENCENT_THEMES.get(ti);
            ctx.progress().emit("sync", "腾讯分类 " + theme.name() + "（" + (ti + 1) + "/" + TENCENT_THEMES.size() + "）");
            try {
                for (int page = 1; page <= themePages; page++) {
                    String url = String.format(TENCENT_THEME_URL, theme.id(), page);
                    int n = fetchAndUpsertTencentPage(client, url, theme.name(), -1, 0);
                    saved += n;
                    if (n == 0) break;
                    sleeper.sleep(TENCENT_PAGE_SLEEP_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                log.warn("tencent theme {} sync failed: {}", theme.name(), e.getMessage());
            }
        }

        // 阶段 2：全站热度总榜翻页（每条自带完整多标签，覆盖全站 3,200+ 部头部）
        int hotPages = maxPagesOverride != null
                ? Math.min(Math.max(maxPagesOverride, 1), 269)
                : TENCENT_DEFAULT_HOT_PAGES;
        for (int page = 1; page <= hotPages; page++) {
            if (page == 1 || page % 15 == 0) {
                ctx.progress().emit("sync", "腾讯全站热度 页 " + page + "/" + hotPages);
            }
            try {
                String url = String.format(TENCENT_ALL_HOT_URL, page);
                int baseRank = (page - 1) * 12;
                int n = fetchAndUpsertTencentPage(client, url, null, baseRank, hotPages * 12);
                saved += n;
                if (n == 0) break;
                sleeper.sleep(TENCENT_PAGE_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                log.warn("tencent all-hot page {} failed: {}", page, e.getMessage());
            }
        }

        if (source != null) {
            saved += syncProgressive(ctx, source);
        }
        return saved;
    }

    private int fetchAndUpsertTencentPage(HttpClient client, String url, String themeName,
                                          int baseRank, int horizon) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", WEB_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Referer", "https://ac.qq.com/Comic/all")
                .GET().build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("tencent url {} HTTP {}", url, resp.statusCode());
            return 0;
        }
        List<ParsedTencentItem> items = parseTencentListPage(resp.body(), themeName);
        for (int idx = 0; idx < items.size(); idx++) {
            ParsedTencentItem item = items.get(idx);
            double popScore = baseRank >= 0 ? CatalogRanking.popularityFromRank(baseRank + idx, horizon) : 0.0;
            catalog.upsert(UUID.randomUUID(), "tencent", item.id(), item.title(),
                    item.author(), item.coverUrl(), item.remarks(), item.tags(), null,
                    CatalogRanking.REGION_CN, CatalogRanking.CONF_PRIOR, popScore);
        }
        return items.size();
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

    /** 渐进式：rank/sections + 题材页可见条目入库（规则源通用路径）。 */
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
        if (!"tencent".equals(source.id()) && !"copymanga".equals(source.id())
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
                    sleeper.sleep(GENERIC_PAGE_SLEEP_MS);
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
