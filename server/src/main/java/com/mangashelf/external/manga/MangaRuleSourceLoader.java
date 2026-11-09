package com.mangashelf.external.manga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** 从 manga_source_rules 加载规则源实例（M23）；单行损坏不阻断其余源。 */
@Component
public class MangaRuleSourceLoader {

    private static final Logger log = LoggerFactory.getLogger(MangaRuleSourceLoader.class);

    private final MangaSourceRuleRepository repository;
    private final MangaRuleEngine engine;
    private final ObjectMapper objectMapper;

    public MangaRuleSourceLoader(MangaSourceRuleRepository repository, MangaRuleEngine engine,
                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.engine = engine;
        this.objectMapper = objectMapper;
    }

    public List<RuleMangaSource> load() {
        List<RuleMangaSource> out = new ArrayList<>();
        for (MangaSourceRuleRepository.RuleRow row : repository.findEnabled()) {
            try {
                out.add(new RuleMangaSource(row.id(), row.name(), row.note(), row.rules(),
                        engine, objectMapper));
            } catch (Exception e) {
                log.error("规则源 {} 加载失败：{}", row.id(), e.getMessage());
            }
        }
        log.info("规则漫画源已加载：{}", out.stream().map(RuleMangaSource::id).toList());
        return out;
    }
}
