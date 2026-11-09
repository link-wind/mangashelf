package com.mangashelf.external.manga;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 漫画数据源注册表（M17）：按 id 查找；新增源只需注册为 Spring Bean。 */
@Component
public class MangaSourceRegistry {

    private final Map<String, MangaSource> sources = new LinkedHashMap<>();

    public MangaSourceRegistry(List<MangaSource> beans) {
        for (MangaSource s : beans) {
            sources.put(s.id(), s);
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
