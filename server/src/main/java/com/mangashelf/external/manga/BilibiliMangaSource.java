package com.mangashelf.external.manga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 哔哩漫画（M17）：仅匿名可用的 HomeHot 热门榜作为元数据/排序来源。
 * 详情与搜索需要登录态（实测 code=99），因此只声明 RANK 能力；
 * 阅读跳转官方页面，不在站内托管。
 */
@Component
public class BilibiliMangaSource implements MangaSource {

    private static final Logger log = LoggerFactory.getLogger(BilibiliMangaSource.class);
    private static final String HOME_HOT =
            "https://manga.bilibili.com/twirp/comic.v1.Comic/HomeHot?device=pc";
    private static final String DETAIL_URL = "https://manga.bilibili.com/detail/mc";
    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private final ObjectMapper objectMapper;
    private final MangaMatcher matcher;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12)).build();

    private final MangaBrowserResolveService browserResolver;
    private final String cookie;
    private final String storageState;
    private BiliMangaCanvasSource canvasSource;

    public BilibiliMangaSource(ObjectMapper objectMapper, MangaMatcher matcher,
                               MangaBrowserResolveService browserResolver,
                               @Value("${mangashelf.bilibili.cookie:}") String cookie,
                               @Value("${mangashelf.bilibili.storage-state:}") String storageState) {
        this.objectMapper = objectMapper;
        this.matcher = matcher;
        this.browserResolver = browserResolver;
        this.cookie = cookie;
        this.storageState = storageState;
    }

    @Override
    public String id() {
        return "bilibili";
    }

    @Override
    public String name() {
        return "哔哩漫画";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.RANK, Capability.READ);
    }

    @Override
    public String note() {
        return "热门榜元数据；免费章节可站内阅读（canvas 截图）";
    }

    /** READ 委托 canvas 截图源（懒初始化，避免构造环）。 */
    private synchronized BiliMangaCanvasSource canvas() {
        if (canvasSource == null) {
            canvasSource = new BiliMangaCanvasSource(browserResolver, objectMapper, cookie, storageState);
        }
        return canvasSource;
    }

    @Override
    public Pages pages(String chapterId) throws Exception {
        return canvas().pages(chapterId);
    }

    @Override
    public List<RankItem> rank(int limit) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(HOME_HOT))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("User-Agent", UA)
                .header("Referer", "https://manga.bilibili.com/")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("哔哩漫画 HTTP " + resp.statusCode());
        }
        JsonNode root = objectMapper.readTree(resp.body());
        if (root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("哔哩漫画返回 code=" + root.path("code").asInt());
        }
        List<RankItem> out = new ArrayList<>();
        for (JsonNode c : root.path("data")) {
            if (out.size() >= Math.max(1, limit)) break;
            List<String> authors = new ArrayList<>();
            for (JsonNode a : c.path("author")) authors.add(a.asText());
            String comicId = c.path("comic_id").asText();
            String latest = c.path("last_short_title").asText(c.path("last_ord").asText(""));
            String cover = c.path("vertical_cover").asText("");
            // 竖版封面原图可达数 MB，按宽度降采样
            if (cover.contains("hdslb.com") && !cover.contains("@")) {
                cover = cover + "@480w.jpg";
            }
            String title = c.path("title").asText();
            // 跨源桥接：仅对前 12 条做匹配（首次约 10s，之后走缓存），避免榜单接口过慢
            String readId = (out.size() < 12 ? matcher.findReadable(title) : java.util.Optional.<String>empty())
                    .orElse(null);
            out.add(new RankItem("bilibili", comicId, title, authors, cover,
                    c.path("is_finish").asInt(0) == 1,
                    latest.isBlank() ? "" : "更新至 " + latest,
                    readId != null, DETAIL_URL + comicId,
                    readId == null ? null : "mangadex", readId));
        }
        log.debug("bilibili hot list size={}", out.size());
        return out;
    }
}
