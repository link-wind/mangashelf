package com.mangashelf.external.manga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 哔哩匿名热门榜、章节目录与公开章节阅读。 */
@Component
@Profile("api")
public class BilibiliMangaSource implements MangaSource {

    private static final Logger log = LoggerFactory.getLogger(BilibiliMangaSource.class);
    private static final String DETAIL_URL = "https://manga.bilibili.com/detail/mc";
    private final BilibiliHttpClient upstream;
    private final BilibiliReaderService reader;
    public BilibiliMangaSource(BilibiliHttpClient upstream, BilibiliReaderService reader) {
        this.upstream = upstream;
        this.reader = reader;
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
        return Set.of(Capability.RANK, Capability.DETAIL, Capability.READ);
    }

    @Override
    public String note() {
        return "热门榜、章节列表；公开免费章节可站内阅读";
    }

    @Override
    public Detail detail(String mangaId, String lang) throws Exception {
        return reader.detail(mangaId);
    }

    @Override
    public Pages pages(String chapterId) throws Exception {
        return reader.pages(chapterId);
    }

    @Override
    public List<RankItem> rank(int limit) throws Exception {
        JsonNode data = upstream.hot();
        List<RankItem> out = new ArrayList<>();
        for (JsonNode c : data) {
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
            out.add(new RankItem("bilibili", comicId, title, authors, cover,
                    c.path("is_finish").asInt(0) == 1,
                    latest.isBlank() ? "" : "更新至 " + latest,
                    false, DETAIL_URL + comicId, id(), comicId));
        }
        log.debug("bilibili hot list size={}", out.size());
        return out;
    }
}
