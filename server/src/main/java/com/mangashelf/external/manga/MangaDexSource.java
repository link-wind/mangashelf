package com.mangashelf.external.manga;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** MangaDex（M17）：支持榜单、搜索、详情与站内阅读。 */
@Component
public class MangaDexSource implements MangaSource {

    private final MangaDexClient client;

    public MangaDexSource(MangaDexClient client) {
        this.client = client;
    }

    @Override
    public String id() {
        return "mangadex";
    }

    @Override
    public String name() {
        return "MangaDex";
    }

    @Override
    public String language() {
        return "multi";
    }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(Capability.RANK, Capability.SEARCH, Capability.DETAIL, Capability.READ);
    }

    @Override
    public String note() {
        return "开放 API；可站内阅读托管章节（外链章节除外）";
    }

    @Override
    public List<RankItem> rank(int limit) throws Exception {
        List<RankItem> out = new ArrayList<>();
        int i = 0;
        for (MangaDexClient.Manga m : client.popular(limit)) {
            i++;
            out.add(new RankItem("mangadex", m.id(), m.title(), List.of(),
                    m.coverUrl(), "completed".equals(m.status()), "", true, null,
                    "mangadex", m.id()));
        }
        return out;
    }

    @Override
    public List<SearchItem> search(String query, String lang, int limit) throws Exception {
        List<SearchItem> out = new ArrayList<>();
        for (MangaDexClient.Manga m : client.search(query, lang, limit)) {
            out.add(new SearchItem("mangadex", m.id(), m.title(), m.altTitle(), m.coverUrl(), m.year()));
        }
        return out;
    }

    @Override
    public Detail detail(String mangaId, String lang) throws Exception {
        MangaDexClient.Manga m = client.manga(mangaId);
        List<ChapterItem> chapters = new ArrayList<>();
        for (MangaDexClient.Chapter c : client.chapters(mangaId, lang, 500)) {
            chapters.add(new ChapterItem(c.id(),
                    c.chapter().isBlank() ? "?" : c.chapter(), c.title(), c.lang(), c.pages(), c.external()));
        }
        return new Detail(new SearchItem("mangadex", m.id(), m.title(), m.altTitle(),
                m.coverUrl(), m.year()), m.description(), m.status(), m.year(), m.tags(), chapters);
    }

    @Override
    public Pages pages(String chapterId) throws Exception {
        MangaDexClient.ChapterPages p = client.pages(chapterId);
        if (p.hash().isBlank() || p.pages().isEmpty()) {
            return new Pages(List.of(), List.of(), true);
        }
        return new Pages(absolute(p.baseUrl(), "data", p.hash(), p.pages()),
                absolute(p.baseUrl(), "data-saver", p.hash(), p.dataSaver()), false);
    }

    private static List<String> absolute(String base, String kind, String hash, List<String> files) {
        List<String> out = new ArrayList<>(files.size());
        for (String f : files) out.add(base + "/" + kind + "/" + hash + "/" + f);
        return out;
    }
}
