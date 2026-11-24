package com.mangashelf.ingestion;

import com.mangashelf.config.MangaShelfProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 任务执行器（DESIGN 11.2）：租约领取 → 心跳续约 → 执行 → 匹配租约令牌提交结果。
 * 仅对显式白名单 {@link TransientJobException} 在未达上限时执行指数退避重试；
 * 其他异常按永久错误直接落 FAILED（AGENTS.md）。
 */
@Component
@Profile("worker")
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final JobRepository jobs;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final MangaShelfProperties props;
    private final TransactionTemplate tx;
    private final JobMetrics metrics;
    private final Map<String, JobHandler> handlers;
    private final ScheduledExecutorService heartbeatPool = Executors.newScheduledThreadPool(1);

    public JobExecutor(JobRepository jobs, OutboxRepository outbox, ObjectMapper objectMapper,
                       MangaShelfProperties props, TransactionTemplate tx, JobMetrics metrics,
                       java.util.List<JobHandler> handlerList) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.props = props;
        this.tx = tx;
        this.metrics = metrics;
        this.handlers = handlerList.stream()
                .collect(Collectors.toUnmodifiableMap(JobHandler::kind, Function.identity()));
    }

    public void execute(JobMessage msg) {
        JobRepository.ClaimResult claim = jobs.tryClaim(msg.jobId(), props.jobs().leaseSeconds(), false);
        switch (claim.outcome()) {
            case CLAIMED -> runClaimed(claim);
            case FINISHED -> log.debug("job {} already finished, duplicate notification", msg.jobId());
            case ACTIVE_LEASE -> log.debug("job {} holds active lease, duplicate notification", msg.jobId());
            case EXPIRED_LEASE -> log.warn(
                    "job {} lease expired; recovery scan will re-dispatch, skip for now", msg.jobId());
        }
    }

    /** 允许集成测试在指定租约下直接驱动执行与 Fencing 校验。 */
    void runClaimed(JobRepository.ClaimResult claim) {
        Job job = claim.job();
        UUID token = claim.leaseToken();
        long leaseSeconds = props.jobs().leaseSeconds();
        long heartbeatSeconds = props.jobs().heartbeatSeconds();
        long startNs = System.nanoTime();

        log.info("job {} claimed, kind={} attempt={}", job.id(), job.kind(), job.attempt());
        ScheduledFuture<?> heartbeat = heartbeatPool.scheduleAtFixedRate(
                () -> jobs.heartbeat(job.id(), token, leaseSeconds, null),
                heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        try {
            JobHandler handler = handlers.get(job.kind());
            if (handler == null) {
                commitFailure(job, token, "NO_HANDLER", "no handler for kind " + job.kind(), startNs);
                return;
            }
            int attempt = job.attempt();
            Path workDir = Path.of(props.processing().workRoot())
                    .resolve(job.id().toString())
                    .resolve("attempt-" + attempt);
            JobContext ctx = new JobContext(job, attempt, token, workDir,
                    (stage, message) -> tx.executeWithoutResult(status ->
                            jobs.appendEvent(job.id(), "progress", stage, message)),
                    () -> jobs.isCancelRequested(job.id()));
            if (ctx.cancelled()) {
                commitResult(job, token, new JobResult(Job.STATUS_CANCELLED, "cancelled",
                        null, "CANCELLED", "任务已被所有者取消"), startNs);
                return;
            }
            JobResult result = handler.run(ctx);
            commitResult(job, token, result, startNs);
        } catch (TransientJobException e) {
            handleTransientFailure(job, token, e, startNs);
        } catch (Exception e) {
            log.error("job {} failed unexpectedly", job.id(), e);
            commitFailure(job, token, "UNEXPECTED", String.valueOf(e.getMessage()), startNs);
        } finally {
            heartbeat.cancel(false);
        }
    }

    private void handleTransientFailure(Job job, UUID token, TransientJobException e, long startNs) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNs);
        if (job.attempt() >= job.maxAttempt()) {
            log.warn("job {} transient error exhausted max_attempt={}: {}", job.id(), job.maxAttempt(), e.getMessage());
            commitFailure(job, token, e.errorCode(), e.getMessage(), startNs);
            return;
        }
        long baseSeconds = Math.min(30L, 1L << Math.min(job.attempt(), 5));
        long jitterMs = ThreadLocalRandom.current().nextLong(0, 1000);
        Duration delay = Duration.ofSeconds(baseSeconds).plusMillis(jitterMs);
        log.warn("job {} transient failure (attempt {}/{}), requeue after {}ms: {}",
                job.id(), job.attempt(), job.maxAttempt(), delay.toMillis(), e.getMessage());
        Boolean requeued = tx.execute(status -> {
            boolean ok = jobs.requeueOnTransientFailure(job.id(), token, e.errorCode(), e.getMessage());
            if (!ok) {
                return false;
            }
            int dispatchNo = jobs.nextDispatchNo(job.id());
            String payload;
            try {
                payload = objectMapper.writeValueAsString(
                        new JobMessage(JobMessage.SCHEMA_VERSION, job.id(), dispatchNo, job.kind()));
            } catch (Exception ex) {
                throw new IllegalStateException("retry payload serialization failed", ex);
            }
            outbox.insertDelayed(job.id(), dispatchNo, payload, delay);
            jobs.appendEvent(job.id(), "progress", "retry",
                    "瞬时错误，退避重试（dispatch " + dispatchNo + "）：" + e.getMessage());
            return true;
        });
        if (Boolean.TRUE.equals(requeued)) {
            metrics.recordJobOutcome(job.kind(), "retry", elapsed);
        } else {
            metrics.recordStaleLeaseRejected();
            metrics.recordJobOutcome(job.kind(), "stale_rejected", elapsed);
            log.warn("job {} transient requeue rejected (stale lease)", job.id());
        }
    }

    private void commitResult(Job job, UUID token, JobResult result, long startNs) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNs);
        if ("STALE_LEASE".equals(result.errorCode())) {
            metrics.recordStaleLeaseRejected();
            metrics.recordJobOutcome(job.kind(), "stale_rejected", elapsed);
            log.warn("job {} handler reported STALE_LEASE, discarding result", job.id());
            return;
        }
        Boolean committed = tx.execute(status -> {
            boolean ok = jobs.commitResult(job.id(), token, result.status(), result.stage(),
                    result.progress(), result.errorCode(), result.errorMessage());
            if (!ok) {
                return false;
            }
            jobs.appendEvent(job.id(), "result", result.stage(),
                    Job.STATUS_SUCCEEDED.equals(result.status()) ? "任务完成" : "任务失败：" + result.errorMessage());
            return true;
        });
        if (Boolean.TRUE.equals(committed)) {
            String outcome = Job.STATUS_SUCCEEDED.equals(result.status()) ? "succeeded"
                    : Job.STATUS_CANCELLED.equals(result.status()) ? "cancelled" : "failed";
            metrics.recordJobOutcome(job.kind(), outcome, elapsed);
        } else {
            metrics.recordStaleLeaseRejected();
            metrics.recordJobOutcome(job.kind(), "stale_rejected", elapsed);
            log.warn("job {} result commit rejected (stale lease), discarding", job.id());
        }
    }

    private void commitFailure(Job job, UUID token, String errorCode, String message, long startNs) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNs);
        try {
            Boolean committed = tx.execute(status -> {
                boolean ok = jobs.commitResult(job.id(), token, Job.STATUS_FAILED, errorCode, null,
                        errorCode, message);
                if (ok) {
                    jobs.appendEvent(job.id(), "result", errorCode, "任务失败：" + message);
                }
                return ok;
            });
            if (Boolean.TRUE.equals(committed)) {
                metrics.recordJobOutcome(job.kind(), "failed", elapsed);
            } else {
                metrics.recordStaleLeaseRejected();
                metrics.recordJobOutcome(job.kind(), "stale_rejected", elapsed);
            }
        } catch (Exception e) {
            log.error("failed to commit failure for job {}", job.id(), e);
        }
    }
}
