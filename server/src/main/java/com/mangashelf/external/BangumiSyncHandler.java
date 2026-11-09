package com.mangashelf.external;

import com.mangashelf.ingestion.Job;
import com.mangashelf.ingestion.JobContext;
import com.mangashelf.ingestion.JobHandler;
import com.mangashelf.ingestion.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** bangumi.sync：本季新番日历同步 + 目录桥接（M9）。 */
@Component
public class BangumiSyncHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(BangumiSyncHandler.class);

    private final BangumiService bangumi;
    private final ExternalRepository externals;
    private final ExternalCatalogRepository catalogItems;

    public BangumiSyncHandler(BangumiService bangumi, ExternalRepository externals,
                              ExternalCatalogRepository catalogItems) {
        this.bangumi = bangumi;
        this.externals = externals;
        this.catalogItems = catalogItems;
    }

    @Override
    public String kind() {
        return Job.KIND_BANGUMI_SYNC;
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        ctx.progress().emit("bangumi", "拉取本季新番日历");
        int subjects = bangumi.syncCalendar();
        ctx.progress().emit("bangumi", "日历 " + subjects + " 部，开始标题匹配");
        // 反查源优先取最近一次成功目录同步所用的源（名称排序会命中历史 mock 源）
        ExternalSource source = catalogItems.findLatestCatalogSyncSourceId()
                .flatMap(externals::findSource)
                .orElseGet(() -> externals.listSources(true).stream().findFirst().orElse(null));
        var stats = bangumi.matchCalendarToCatalog(source);
        log.info("job {}: bangumi calendar {} subjects, matched local {} + search {}",
                ctx.job().id(), subjects, stats.local(), stats.viaSearch());
        return JobResult.success("bangumi:" + subjects
                + ",local:" + stats.local() + ",search:" + stats.viaSearch());
    }
}
