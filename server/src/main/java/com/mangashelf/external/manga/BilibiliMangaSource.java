package com.mangashelf.external.manga;

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
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12)).build();

    public BilibiliMangaSource(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
        return Set.of(Capability.RANK);
    }

    @Override
    public String note() {
        return "热门榜元数据；阅读请前往哔哩漫画";
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
            out.add(new RankItem("bilibili", comicId, c.path("title").asText(), authors,
                    cover,
                    c.path("is_finish").asInt(0) == 1,
                    latest.isBlank() ? "" : "更新至 " + latest,
                    false, DETAIL_URL + comicId));
        }
        log.debug("bilibili hot list size={}", out.size());
        return out;
    }
}
