package com.mangashelf.external.manga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 漫画数据源注册表（M17/M23）：代码源为 Spring Bean，规则源来自
 * manga_source_rules 表（MangaRuleSourceLoader）；按 id 查找。
 */
@Component
public class MangaSourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(MangaSourceRegistry.class);

    private final Map<String, MangaSource> sources = new LinkedHashMap<>();

    public MangaSourceRegistry(List<MangaSource> beans, MangaRuleSourceLoader loader) {
        for (MangaSource s : beans) {
            sources.put(s.id(), s);
        }
        try {
            for (RuleMangaSource s : loader.load()) {
                sources.put(s.id(), s);
            }
        } catch (Exception e) {
            // 规则源不可用不阻断启动；代码源仍可用，错误显式记录
            log.error("规则漫画源加载失败：{}", e.getMessage());
        }
    }

    public List<MangaSource> all() {
        return List.copyOf(sources.values());
    }

    public Optional<MangaSource> find(String id) {
        return Optional.ofNullable(sources.get(id));
    }

    public record SourceInfo(String id, String name, List<String> capabilities, String note) {}

    public List<SourceInfo> describe() {
        return sources.values().stream()
                .map(s -> new SourceInfo(s.id(), s.name(),
                        s.capabilities().stream().map(Enum::name).sorted().toList(), s.note()))
                .toList();
    }
}
