package com.mangashelf.ingestion;

import com.mangashelf.catalog.CatalogRepository;
import com.mangashelf.config.MangaShelfProperties;
import com.mangashelf.external.ExternalCatalogRepository;
import com.mangashelf.external.ExternalRepository;
import com.mangashelf.external.ExternalSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 目录与新番的周期同步（M10）：只在 worker 角色运行。
 * 资源目录：每 30 分钟增量（h=24）；Bangumi 日历：每 6 小时。
 * 事实来源仍是 jobs 表——这里只负责按时创建任务，重复触发由 JobCreationService 幂等键收敛。
 */
@Component
@Profile("worker")
public class ScheduledSyncService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledSyncService.class);
    private static final Duration CATALOG_INTERVAL = Duration.ofMinutes(30);
    private static final Duration BANGUMI_INTERVAL = Duration.ofHours(6);
    private static final List<Integer> ANIME_CATEGORIES = List.of(29, 30, 31, 32, 33);

    private final ExternalCatalogRepository catalogItems;
    private final ExternalRepository externals;
    private final JobCreationService jobCreation;
    private final MangaShelfProperties props;

    public ScheduledSyncService(ExternalCatalogRepository catalogItems, ExternalRepository externals,
                                JobCreationService jobCreation, MangaShelfProperties props) {
        this.catalogItems = catalogItems;
        this.externals = externals;
        this.jobCreation = jobCreation;
        this.props = props;
    }

    /** 每 10 分钟检查一次：目录超过 30 分钟未同步则触发增量同步。 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 600_000)
    public void syncCatalogIfStale() {
        try {
            ExternalSource source = catalogItems.findLatestCatalogSyncSourceId()
                    .flatMap(externals::findSource)
                    .orElseGet(() -> externals.listSources(true).stream().findFirst().orElse(null));
            if (source == null) return;
            var latest = catalogItems.findLatestSyncJob(source.id());
            if (latest.isPresent() && latest.get().createdAt()
                    .isAfter(Instant.now().minus(CATALOG_INTERVAL))) {
                return;
            }
            String params = "{\"mode\":\"incremental\",\"hours\":24,\"categories\":[29,30,31,32,33]}";
            long window = Instant.now().getEpochSecond() / 1800; // 30 分钟窗口
            jobCreation.create(Job.KIND_CATALOG_SYNC, props.demoOwnerId(), null, null, null,
                    null, null, source.id(), params, "catalog.auto:" + source.id() + ":" + window);
            log.info("scheduled catalog sync triggered for {}", source.name());
        } catch (Exception e) {
            log.warn("scheduled catalog sync failed: {}", e.getMessage());
        }
    }

    /** 每 10 分钟检查一次：Bangumi 日历超过 6 小时未同步则触发。 */
    @Scheduled(initialDelay = 120_000, fixedDelay = 600_000)
    public void syncBangumiIfStale() {
        try {
            var latest = catalogItems.findLatestBangumiSyncJob();
            if (latest.isPresent() && latest.get().createdAt()
                    .isAfter(Instant.now().minus(BANGUMI_INTERVAL))) {
                return;
            }
            long window = Instant.now().getEpochSecond() / 21600; // 6 小时窗口
            jobCreation.create(Job.KIND_BANGUMI_SYNC, props.demoOwnerId(), null, null, null,
                    null, null, null, null, "bangumi.auto:" + window);
            log.info("scheduled bangumi sync triggered");
        } catch (Exception e) {
            log.warn("scheduled bangumi sync failed: {}", e.getMessage());
        }
    }
}
