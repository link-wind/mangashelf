package com.mangashelf.external.manga;

import com.mangashelf.ingestion.Job;
import com.mangashelf.ingestion.JobContext;
import com.mangashelf.ingestion.JobHandler;
import com.mangashelf.ingestion.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * manga.catalog.sync：漫画目录同步（manga-catalog-sync）。
 * params.sourceId 指定单源（逐源任务，失败隔离）：
 * - copymanga：遍历题材 × 分页全量抓取（限速：题材间 2s、分页间 500ms）
 * - bilibili / 规则源：rank/sections 可见条目入库（渐进式）
 * 重复同步幂等（(source, source_item_id) upsert）。
 */
@Component
public class MangaCatalogSyncHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(MangaCatalogSyncHandler.class);
    private static final String COPY_API = "https://api.manga2026.xyz/api/v3";
    /** 题材间/分页间限速（毫秒）。 */
    private static final long GENRE_SLEEP_MS = 2000;
    private static final long PAGE_SLEEP_MS = 500;
    private static final int PAGE_SIZE = 60;

    private final MangaSourceRegistry registry;
    private final MangaCatalogRepository catalog;
    private final ObjectMapper objectMapper;
    private final Sleeper sleeper;

    /** 可注入的休眠（测试跳过真实等待）。 */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MangaCatalogSyncHandler(MangaSourceRegistry registry, MangaCatalogRepository catalog,
                                   ObjectMapper objectMapper) {
        this(registry, catalog, objectMapper, millis -> Thread.sleep(millis));
    }

    MangaCatalogSyncHandler(MangaSourceRegistry registry, MangaCatalogRepository catalog,
                            ObjectMapper objectMapper, Sleeper sleeper) {
        this.registry = registry;
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.sleeper = sleeper;
    }

    @Override
    public String kind() {
        return "manga.catalog.sync";
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

    /** 拷贝漫画：逐题材分页全量。 */
    private int syncCopyManga(JobContext ctx, MangaSource source) throws Exception {
        List<String> genres = source.genres();
        if (genres.isEmpty()) {
            ctx.progress().emit("sync", "源无题材清单，跳过全量同步");
            return 0;
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
        int saved = 0;
        for (int gi = 0; gi < genres.size(); gi++) {
            String genre = genres.get(gi);
            ctx.progress().emit("sync", "题材 " + genre + "（" + (gi + 1) + "/" + genres.size() + "）");
            int offset = 0;
            while (offset < 2000) { // 单题材上限：防异常源无限分页
                JsonNode list = fetchCopyFilter(client, genre, offset);
                int count = 0;
                for (JsonNode item : list) {
                    String id = item.path("path_word").asText("");
                    String name = item.path("name").asText("");
                    if (id.isBlank() || name.isBlank()) continue;
                    List<String> tags = new ArrayList<>();
                    item.path("theme").forEach(t -> {
                        String tag = t.path("name").asText("").trim();
                        if (!tag.isBlank()) tags.add(tag);
                    });
                    if (tags.isEmpty()) tags.add(genre);
                    catalog.upsert(UUID.randomUUID(), "copymanga", id, name, "",
                            item.path("cover").asText(""), item.path("status").path("display").asText(""),
                            tags, null);
                    saved++;
                    count++;
                }
                if (count == 0) break;
                offset += count;
                sleeper.sleep(PAGE_SLEEP_MS);
            }
            sleeper.sleep(GENRE_SLEEP_MS);
        }
        return saved;
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
        if (source.capabilities().contains(MangaSource.Capability.SEARCH) && !source.genres().isEmpty()) {
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
