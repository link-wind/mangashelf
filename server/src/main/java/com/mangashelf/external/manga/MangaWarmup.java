package com.mangashelf.external.manga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 启动预热（M34）：后台拉一次聚合推荐，填充各源分区缓存，避免首页首开等待。 */
@Component
@Profile("api")
public class MangaWarmup {

    private static final Logger log = LoggerFactory.getLogger(MangaWarmup.class);

    private final MangaDiscoveryService discovery;

    public MangaWarmup(MangaDiscoveryService discovery) {
        this.discovery = discovery;
    }

    @Order(2)
    @EventListener(ApplicationReadyEvent.class)
    public void warm() {
        Thread t = new Thread(() -> {
            try {
                long started = System.currentTimeMillis();
                discovery.discover(12);
                log.info("漫画首页预热完成，耗时 {} ms", System.currentTimeMillis() - started);
            } catch (Exception e) {
                log.warn("漫画首页预热失败：{}", e.getMessage());
            }
        }, "manga-warmup");
        t.setDaemon(true);
        t.start();
    }
}
