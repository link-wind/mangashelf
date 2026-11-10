package com.mangashelf.external.manga;

import tools.jackson.databind.ObjectMapper;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 规则驱动的漫画源（M23）：一条 manga_source_rules 行 = 一个源实例。
 * 能力声明从规则节推导（有 rank 节即支持 RANK），解析全部委托 MangaRuleEngine。
 */
public class RuleMangaSource implements MangaSource {

    private final String id;
    private final String name;
    private final String note;
    private final String rulesJson;
    private final Set<Capability> capabilities;
    private final String language;
    private final MangaRuleEngine engine;

    public RuleMangaSource(String id, String name, String note, String rulesJson,
                           MangaRuleEngine engine, ObjectMapper objectMapper) {
        this.id = id;
        this.name = name;
        this.note = note;
        this.rulesJson = rulesJson;
        this.engine = engine;
        EnumSet<Capability> caps = EnumSet.noneOf(Capability.class);
        var root = objectMapper.readTree(rulesJson);
        for (Capability c : Capability.values()) {
            // 规则节名：rank/search/detail/pages，READ 对应 pages 节
            String section = c == Capability.READ ? "pages" : c.name().toLowerCase();
            if (!root.path(section).isMissingNode()) {
                caps.add(c);
            }
        }
        this.capabilities = caps;
        this.language = root.path("language").asText("zh");
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
    public List<RankItem> rank(int limit) throws Exception {
        return engine.rank(rulesJson, id, limit);
    }

    @Override
    public List<Section> sections(int limit) throws Exception {
        return engine.rankSections(rulesJson, id, limit);
    }

    @Override
    public List<SearchItem> search(String query, String lang, int limit) throws Exception {
        return engine.search(rulesJson, id, query, limit);
    }

    @Override
    public Detail detail(String mangaId, String lang) throws Exception {
        return engine.detail(rulesJson, id, mangaId);
    }

    @Override
    public Pages pages(String chapterId) throws Exception {
        return engine.pages(rulesJson, chapterId);
    }
}
