package com.mangashelf.ingestion;

import com.mangashelf.animation.AnimationRevision;
import com.mangashelf.animation.AnimationVerifyHandler;
import com.mangashelf.config.MangaShelfProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 PostgreSQL + RabbitMQ Worker 并发与租约 Fencing 故障注入测试：
 * 覆盖多 Worker 争抢、过期 Worker 业务写入与结果提交被 Fencing 拒绝（含 AnimationVerifyHandler）、
 * 白名单瞬时错误上限退避重试 vs 永久错误直接终止、以及单条恢复隔离。
 */
class WorkerLeaseFencingIT extends ReliabilityContainersBase {

    @Autowired
    private JobCreationService jobCreation;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private LeaseRecoveryScanner recoveryScanner;

    @Autowired
    private AnimationVerifyHandler animationVerifyHandler;

    @Autowired
    private JobMetrics metrics;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MangaShelfProperties props;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void concurrentWorkersClaimAtMostOneLeaseHolder() throws Exception {
        UUID ownerId = createTestUser();
        UUID sessionId = createUploadSession(ownerId);
        UUID jobId = jobCreation.create(Job.KIND_VERIFY, ownerId, sessionId,
                null, null, null, null, null, null, "concurrent-claim-" + sessionId);

        int workers = 4;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JobRepository.ClaimResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return jobs.tryClaim(jobId, 60, false);
                }));
            }
            ready.await();
            start.countDown();

            int claimedCount = 0;
            int activeCount = 0;
            for (Future<JobRepository.ClaimResult> f : futures) {
                var outcome = f.get().outcome();
                if (outcome == JobRepository.ClaimResult.Outcome.CLAIMED) claimedCount++;
                else if (outcome == JobRepository.ClaimResult.Outcome.ACTIVE_LEASE) activeCount++;
            }
            assertEquals(1, claimedCount, "同一时刻最多只能有一个有效租约持有者");
            assertEquals(workers - 1, activeCount);
        }

        Job job = jobs.findById(jobId).orElseThrow();
        assertEquals(Job.STATUS_RUNNING, job.status());
        assertEquals(1, job.attempt());
    }

    @Test
    void expiredWorkerCommitAndAnimationRevisionWriteAreRejectedByFencing() throws Exception {
        UUID ownerId = createTestUser();
        UUID revisionId = createAnimationRevisionWithVideo(ownerId);
        UUID jobId = jobCreation.create(Job.KIND_ANIMATION_VERIFY, ownerId, null,
                null, revisionId, null, null, null, null, "fencing-anim-" + revisionId);

        // 1. Worker A 领取租约（attempt=1）后模拟卡死
        JobRepository.ClaimResult claimA = jobs.tryClaim(jobId, 60, false);
        assertEquals(JobRepository.ClaimResult.Outcome.CLAIMED, claimA.outcome());
        UUID tokenA = claimA.leaseToken();
        assertTrue(jobs.isLeaseValid(jobId, tokenA));

        // 2. 租约过期 → LeaseRecoveryScanner 原子恢复回 QUEUED 并写入 dispatch 2 outbox
        jdbc.update("UPDATE jobs SET lease_until = now() - interval '10 seconds' WHERE id = ?", jobId);
        double recoveredBefore = counter("mangashelf.jobs.lease.recovered");
        int recovered = recoveryScanner.recoverBatch(50);
        assertTrue(recovered >= 1);
        assertTrue(counter("mangashelf.jobs.lease.recovered") >= recoveredBefore + 1.0);

        Integer dispatchCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE job_id = ?", Integer.class, jobId);
        assertEquals(2, dispatchCount, "恢复扫描应在同一事务内补写 dispatch_no=2 的 outbox 记录");

        // 3. Worker B 领取新租约（attempt=2），提交有效媒体元数据（12000ms）并完成任务
        JobRepository.ClaimResult claimB = jobs.tryClaim(jobId, 60, false);
        assertEquals(JobRepository.ClaimResult.Outcome.CLAIMED, claimB.outcome());
        UUID tokenB = claimB.leaseToken();
        assertNotEquals(tokenA, tokenB);

        AnimationRevision revision = animations.findRevision(revisionId).orElseThrow();
        JsonNode mediaB = objectMapper.readTree("""
                {"duration_ms":12000,"width":1920,"height":1080,"video_codec":"h264","audio_codec":"aac","has_audio":true}
                """);
        assertTrue(animationVerifyHandler.commitVerifiedIfLeased(jobId, tokenB, revision, null, null, mediaB));
        assertTrue(jobs.commitResult(jobId, tokenB, Job.STATUS_SUCCEEDED, "verified", 100, null, null));

        // 4. 卡死的 Worker A 醒来，尝试以旧 tokenA 覆写动画版本元数据（99999ms）并提交结果
        JsonNode staleMediaA = objectMapper.readTree("""
                {"duration_ms":99999,"width":640,"height":360,"video_codec":"vp9","audio_codec":"opus","has_audio":false}
                """);
        assertFalse(animationVerifyHandler.commitVerifiedIfLeased(jobId, tokenA, revision, null, null, staleMediaA),
                "过期 Worker A 的业务落库必须被 Fencing 拒绝");
        assertFalse(jobs.commitResult(jobId, tokenA, Job.STATUS_FAILED, "stale", null, "ERR", "stale write"),
                "过期 Worker A 的任务提交必须被 Fencing 拒绝");

        // 通过 JobExecutor 路径也验证 stale_rejected 指标递增
        double staleBefore = counter("mangashelf.jobs.lease.stale_rejected");
        JobHandler staleHandler = new JobHandler() {
            @Override public String kind() { return Job.KIND_ANIMATION_VERIFY; }
            @Override public JobResult run(JobContext ctx) { return JobResult.success("late"); }
        };
        JobExecutor executor = new JobExecutor(
                jobs, outbox, objectMapper, props, tx, metrics, List.of(staleHandler));
        executor.runClaimed(claimA);
        assertEquals(staleBefore + 1.0, counter("mangashelf.jobs.lease.stale_rejected"));

        // 最终状态完全保留 Worker B 的有效结果，未被 Worker A 污染
        AnimationRevision finalRev = animations.findRevision(revisionId).orElseThrow();
        assertEquals(AnimationRevision.STATUS_READY, finalRev.status());
        assertEquals(12000L, finalRev.durationMs());
        assertEquals(1920, finalRev.videoWidth());
        Job finalJob = jobs.findById(jobId).orElseThrow();
        assertEquals(Job.STATUS_SUCCEEDED, finalJob.status());
        assertEquals(2, finalJob.attempt());
    }

    @Test
    void transientErrorRetriesUpToMaxAttemptWhilePermanentErrorFailsImmediately() {
        UUID ownerId = createTestUser();

        // 场景 A：白名单瞬时错误（TransientJobException）按上限退避重试，达 max_attempt=3 后终结 FAILED
        UUID sessionTransient = createUploadSession(ownerId);
        UUID transientJobId = jobCreation.create(Job.KIND_VERIFY, ownerId, sessionTransient,
                null, null, null, null, null, null, "transient-retry-" + sessionTransient);

        JobHandler transientHandler = new JobHandler() {
            @Override public String kind() { return Job.KIND_VERIFY; }
            @Override public JobResult run(JobContext ctx) {
                throw new TransientJobException("UPSTREAM_TIMEOUT", "simulated transient timeout");
            }
        };
        JobExecutor transientExecutor = new JobExecutor(
                jobs, outbox, objectMapper, props, tx, metrics, List.of(transientHandler));

        // 第 1 次尝试：退回 QUEUED（stage=RETRY_WAIT）并写入延迟 outbox（dispatch 2）
        transientExecutor.execute(new JobMessage(JobMessage.SCHEMA_VERSION, transientJobId, 1, Job.KIND_VERIFY));
        Job after1 = jobs.findById(transientJobId).orElseThrow();
        assertEquals(Job.STATUS_QUEUED, after1.status());
        assertEquals("RETRY_WAIT", after1.stage());
        assertEquals(1, after1.attempt());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE job_id = ?", Integer.class, transientJobId));

        // 第 2 次尝试：退回 QUEUED（dispatch 3）
        transientExecutor.execute(new JobMessage(JobMessage.SCHEMA_VERSION, transientJobId, 2, Job.KIND_VERIFY));
        Job after2 = jobs.findById(transientJobId).orElseThrow();
        assertEquals(Job.STATUS_QUEUED, after2.status());
        assertEquals(2, after2.attempt());

        // 第 3 次尝试（达到 max_attempt=3）：不再重试，直接终结为 FAILED
        transientExecutor.execute(new JobMessage(JobMessage.SCHEMA_VERSION, transientJobId, 3, Job.KIND_VERIFY));
        Job after3 = jobs.findById(transientJobId).orElseThrow();
        assertEquals(Job.STATUS_FAILED, after3.status());
        assertEquals("UPSTREAM_TIMEOUT", after3.errorCode());
        assertEquals(3, after3.attempt());

        // 场景 B：永久错误（如 IllegalArgumentException）不做自动重试，attempt=1 直接 FAILED
        UUID sessionPerm = createUploadSession(ownerId);
        UUID permJobId = jobCreation.create(Job.KIND_VERIFY, ownerId, sessionPerm,
                null, null, null, null, null, null, "permanent-fail-" + sessionPerm);

        JobHandler permHandler = new JobHandler() {
            @Override public String kind() { return Job.KIND_VERIFY; }
            @Override public JobResult run(JobContext ctx) {
                throw new IllegalArgumentException("invalid format");
            }
        };
        JobExecutor permExecutor = new JobExecutor(
                jobs, outbox, objectMapper, props, tx, metrics, List.of(permHandler));
        permExecutor.execute(new JobMessage(JobMessage.SCHEMA_VERSION, permJobId, 1, Job.KIND_VERIFY));

        Job permAfter = jobs.findById(permJobId).orElseThrow();
        assertEquals(Job.STATUS_FAILED, permAfter.status());
        assertEquals(1, permAfter.attempt());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE job_id = ?", Integer.class, permJobId),
                "永久错误不得写入自动重试 outbox");
    }

    @Test
    void recoveryScannerHandlesMultipleExpiredLeasesIndependently() {
        UUID ownerId = createTestUser();
        UUID j1 = jobCreation.create(Job.KIND_VERIFY, ownerId, createUploadSession(ownerId),
                null, null, null, null, null, null, "batch-rec-1-" + UUID.randomUUID());
        UUID j2 = jobCreation.create(Job.KIND_VERIFY, ownerId, createUploadSession(ownerId),
                null, null, null, null, null, null, "batch-rec-2-" + UUID.randomUUID());

        jobs.tryClaim(j1, 60, false);
        jobs.tryClaim(j2, 60, false);
        // j1 设为已达上限（max_attempt=1），j2 仍可重试（max_attempt=3）
        jdbc.update("UPDATE jobs SET max_attempt = 1, lease_until = now() - interval '5 seconds' WHERE id = ?", j1);
        jdbc.update("UPDATE jobs SET lease_until = now() - interval '5 seconds' WHERE id = ?", j2);

        recoveryScanner.recoverBatch(50);

        assertEquals(Job.STATUS_FAILED, jobs.findById(j1).orElseThrow().status());
        assertEquals("LEASE_EXPIRED", jobs.findById(j1).orElseThrow().errorCode());
        assertEquals(Job.STATUS_QUEUED, jobs.findById(j2).orElseThrow().status());
    }

    private double counter(String name, String... tags) {
        var c = meterRegistry.find(name).tags(tags).counter();
        return c == null ? 0.0 : c.count();
    }
}
