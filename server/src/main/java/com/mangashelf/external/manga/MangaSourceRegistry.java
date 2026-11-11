package com.mangashelf.external.manga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 漫画数据源注册表（M17/M23）：代码源为 Spring Bean，规则源来自
 * manga_source_rules 表（MangaRuleSourceLoader）；按 id 查找。
 * 规则源在 ApplicationReadyEvent 时加载——worker 与 api 并发启动时迁移可能
 * 尚未完成（实测 V15 竞态），失败后延迟重试一次。
 */
@Component
public class MangaSourceRegistry implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(MangaSourceRegistry.class);

    private final Map<String, MangaSource> sources = new LinkedHashMap<>();
    private final MangaRuleSourceLoader loader;

    public MangaSourceRegistry(List<MangaSource> beans, MangaRuleSourceLoader loader) {
        for (MangaSource s : beans) {
            sources.put(s.id(), s);
        }
        this.loader = loader;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            for (MangaSource s : loader.load()) {
                // 代码源优先：规则行不能覆盖同名代码源（如拷贝漫画浏览器源）
                if (sources.putIfAbsent(s.id(), s) != null) {
                    log.warn("规则源 {} 与代码源重名，已忽略规则行", s.id());
                }
            }
        } catch (Exception first) {
            // 典型场景：与 api 并发启动，规则表所属迁移尚未完成；延迟后重试一次
            log.warn("规则漫画源首次加载失败（{}），10s 后重试", first.getMessage());
            Thread retry = new Thread(() -> {
                try {
                    Thread.sleep(10_000);
                    for (MangaSource s : loader.load()) {
                        if (sources.putIfAbsent(s.id(), s) != null) {
                            log.warn("规则源 {} 与代码源重名，已忽略规则行", s.id());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("规则漫画源重试仍失败（不影响代码源）：{}", e.getMessage());
                }
            }, "manga-rule-source-retry");
            retry.setDaemon(true);
            retry.start();
        }
    }

    public List<MangaSource> all() {
        return List.copyOf(sources.values());
    }

    public Optional<MangaSource> find(String id) {
        return Optional.ofNullable(sources.get(id));
    }

    public record SourceInfo(String id, String name, String language,
                             List<String> capabilities, String note) {}

    public List<SourceInfo> describe() {
        return sources.values().stream()
                .map(s -> new SourceInfo(s.id(), s.name(), s.language(),
                        s.capabilities().stream().map(Enum::name).sorted().toList(), s.note()))
                .toList();
    }
}
