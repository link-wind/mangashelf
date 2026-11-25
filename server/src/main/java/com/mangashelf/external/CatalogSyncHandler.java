package com.mangashelf.external;

import com.mangashelf.config.HotspotCache;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * catalog.sync：拉取资源站 MacCMS 目录入资源目录层（M8 D2）。
 * 全量（逐分类逐页）/增量（h=24 更新窗口）；页间 300ms 限速；upsert 幂等可重跑。
 */
@Component
public class CatalogSyncHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(CatalogSyncHandler.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
    private static final long PAGE_SLEEP_MS = 300;
    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private final ExternalRepository externals;
    private final CatalogItemWriter itemWriter;
    private final ObjectMapper objectMapper;
    private final HotspotCache hotspotCache;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT).build();

    public CatalogSyncHandler(ExternalRepository externals, CatalogItemWriter itemWriter,
                              ObjectMapper objectMapper, HotspotCache hotspotCache) {
        this.externals = externals;
        this.itemWriter = itemWriter;
        this.objectMapper = objectMapper;
        this.hotspotCache = hotspotCache;
    }

    @Override
    public String kind() {
        return Job.KIND_CATALOG_SYNC;
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        Job job = ctx.job();
        ExternalSource source = externals.findSource(job.syncSourceId()).orElse(null);
        if (source == null) {
            return JobResult.failure("SOURCE_NOT_FOUND", "资源源不存在");
        }
        JsonNode params = job.params() == null || job.params().isBlank()
                ? objectMapper.createObjectNode() : objectMapper.readTree(job.params());
        boolean incremental = "incremental".equals(params.path("mode").asText("full"));
        int hours = params.path("hours").asInt(24);

        long total = 0;
        if (incremental) {
            total = syncIncremental(ctx, source, params, hours);
        } else {
            JsonNode cats = params.path("categories");
            if (!cats.isArray() || cats.isEmpty()) {
                return JobResult.failure("NO_CATEGORIES", "params 缺少 categories 分类列表");
            }
            for (JsonNode c : cats) {
                total += syncCategory(ctx, source, c.asInt(), c.asText());
            }
        }
        hotspotCache.invalidatePrefix("catalog-");
        String summary = (incremental ? "增量" : "全量") + "同步完成，共 " + total + " 条";
        ctx.progress().emit("sync", summary);
        log.info("job {} catalog sync {}: {}", job.id(), source.name(), summary);
        return JobResult.success("synced:" + total);
    }

    private long syncIncremental(JobContext ctx, ExternalSource source, JsonNode params, int hours) throws Exception {
        JsonNode cats = params.path("categories");
        var allow = new java.util.HashSet<Integer>();
        for (JsonNode c : cats) allow.add(c.asInt());
        int pg = 1;
        long total = 0;
        for (;;) {
            JsonNode d = fetch(ctx, source, "/api.php/provide/vod/?ac=videolist&h=" + hours + "&pg=" + pg);
            if (d == null) break;
            // 增量窗口跨分类返回，仅保留目录关注分类（M9：只收动漫）
            if (allow.isEmpty()) {
                total += upsertPage(ctx, source, d);
            } else {
                var arr = objectMapper.createArrayNode();
                for (JsonNode v : d.path("list")) {
                    if (allow.contains(v.path("type_id").asInt())) arr.add(v);
                }
                var filtered = objectMapper.createObjectNode();
                filtered.set("list", arr);
                total += upsertPage(ctx, source, filtered);
            }
            int pagecount = d.path("pagecount").asInt(1);
            ctx.progress().emit("sync", "增量页 " + pg + "/" + pagecount);
            if (pg >= pagecount) break;
            pg++;
            Thread.sleep(PAGE_SLEEP_MS);
        }
        return total;
    }

    private long syncCategory(JobContext ctx, ExternalSource source, int categoryId, String categoryName)
            throws Exception {
        int pg = 1;
        long total = 0;
        for (;;) {
            JsonNode d = fetch(ctx, source, "/api.php/provide/vod/?ac=videolist&t=" + categoryId + "&pg=" + pg);
            if (d == null) return total;
            total += upsertPage(ctx, source, d);
            int pagecount = d.path("pagecount").asInt(1);
            ctx.progress().emit("sync", "分类 " + categoryName + " 页 " + pg + "/" + pagecount);
            if (pg >= pagecount) break;
            pg++;
            Thread.sleep(PAGE_SLEEP_MS);
        }
        return total;
    }

    /** 拉一页；HTTP 非 200 或 JSON code!=1 返回 null（调用方终止当前循环）。 */
    private JsonNode fetch(JobContext ctx, ExternalSource source, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(source.baseUrl() + path))
                .timeout(HTTP_TIMEOUT).header("User-Agent", UA).GET().build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("资源站返回 HTTP " + resp.statusCode());
        }
        JsonNode d = objectMapper.readTree(resp.body());
        return d.path("code").asInt(0) == 1 ? d : null;
    }

    private long upsertPage(JobContext ctx, ExternalSource source, JsonNode d) {
        long n = 0;
        for (JsonNode v : d.path("list")) {
            itemWriter.upsertVod(source.id(), v);
            n++;
        }
        return n;
    }

}
