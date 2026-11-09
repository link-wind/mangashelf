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

    public BangumiSyncHandler(BangumiService bangumi) {
        this.bangumi = bangumi;
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
        int matched = bangumi.matchCalendarToCatalog();
        log.info("job {}: bangumi calendar {} subjects, matched {} catalog items",
                ctx.job().id(), subjects, matched);
        return JobResult.success("bangumi:" + subjects + ",matched:" + matched);
    }
}
