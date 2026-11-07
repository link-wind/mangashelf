package com.mangashelf.ingestion;

import com.mangashelf.config.MangaShelfProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 任务执行器（DESIGN 11.2）：租约领取 → 心跳续约 → 执行 → 匹配租约令牌提交结果。
 * 链式任务（verify → process）的结果与 outbox 在同一事务提交。
 */
@Component
@Profile("worker")
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final JobRepository jobs;
    private final OutboxRepository outbox;
    private final MangaShelfProperties props;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Map<String, JobHandler> handlers;
    private final ScheduledExecutorService heartbeatPool = Executors.newScheduledThreadPool(1);

    public JobExecutor(JobRepository jobs, OutboxRepository outbox, MangaShelfProperties props,
                       ObjectMapper objectMapper, TransactionTemplate tx, java.util.List<JobHandler> handlerList) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.props = props;
        this.objectMapper = objectMapper;
        this.tx = tx;
        this.handlers = handlerList.stream()
                .collect(Collectors.toUnmodifiableMap(JobHandler::kind, Function.identity()));
    }

    public void execute(JobMessage msg) {
        JobRepository.ClaimResult claim = jobs.tryClaim(msg.jobId(), props.jobs().leaseSeconds());
        switch (claim.outcome()) {
            case CLAIMED -> runClaimed(claim);
            case FINISHED -> log.debug("job {} already finished, duplicate notification", msg.jobId());
            case ACTIVE_LEASE -> log.debug("job {} holds active lease, duplicate notification", msg.jobId());
            case EXPIRED_LEASE -> log.warn(
                    "job {} lease expired; recovery scan will re-dispatch (M1), skip for now", msg.jobId());
        }
    }

    private void runClaimed(JobRepository.ClaimResult claim) {
        Job job = claim.job();
        UUID token = claim.leaseToken();
        long leaseSeconds = props.jobs().leaseSeconds();
        long heartbeatSeconds = props.jobs().heartbeatSeconds();

        log.info("job {} claimed, kind={} attempt={}", job.id(), job.kind(), job.attempt() + 1);
        ScheduledFuture<?> heartbeat = heartbeatPool.scheduleAtFixedRate(
                () -> jobs.heartbeat(job.id(), token, leaseSeconds, null),
                heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        try {
            JobHandler handler = handlers.get(job.kind());
            if (handler == null) {
                commitFailure(job.id(), token, "NO_HANDLER", "no handler for kind " + job.kind());
                return;
            }
            int attempt = job.attempt() + 1;
            Path workDir = Path.of(props.processing().workRoot())
                    .resolve(job.id().toString())
                    .resolve("attempt-" + attempt);
            JobContext ctx = new JobContext(job, attempt, token, workDir,
                    (stage, message) -> jobs.appendEvent(job.id(), "progress", stage, message));
            JobResult result = handler.run(ctx);
            commitResult(job, token, result);
        } catch (Exception e) {
            log.error("job {} failed unexpectedly", job.id(), e);
            commitFailure(job.id(), token, "UNEXPECTED", String.valueOf(e.getMessage()));
        } finally {
            heartbeat.cancel(false);
        }
    }

    private void commitResult(Job job, UUID token, JobResult result) {
        tx.executeWithoutResult(status -> {
            boolean committed = jobs.commitResult(job.id(), token, result.status(), result.stage(),
                    result.progress(), result.errorCode(), result.errorMessage());
            if (!committed) {
                log.warn("job {} result commit rejected (stale lease), discarding", job.id());
                return;
            }
            jobs.appendEvent(job.id(), "result", result.stage(),
                    Job.STATUS_SUCCEEDED.equals(result.status()) ? "任务完成" : "任务失败：" + result.errorMessage());
            if (result.chain() != null) {
                JobResult.ChainedJobSpec chain = result.chain();
                UUID chainedId = jobs.insertOrExisting(chain.kind(), chain.ownerId(),
                        chain.uploadSessionId(), chain.dedupKey());
                outbox.insertFirst(chainedId, payloadOf(chainedId));
                log.info("job {} chained into {} {}", job.id(), chain.kind(), chainedId);
            }
        });
    }

    private void commitFailure(UUID jobId, UUID token, String errorCode, String message) {
        try {
            tx.executeWithoutResult(status -> {
                boolean committed = jobs.commitResult(jobId, token, Job.STATUS_FAILED, errorCode, null,
                        errorCode, message);
                if (committed) {
                    jobs.appendEvent(jobId, "result", errorCode, "任务失败：" + message);
                }
            });
        } catch (Exception e) {
            log.error("failed to commit failure for job {}", jobId, e);
        }
    }

    private String payloadOf(UUID jobId) {
        try {
            return objectMapper.writeValueAsString(
                    new JobMessage(JobMessage.SCHEMA_VERSION, jobId, 1));
        } catch (Exception e) {
            throw new IllegalStateException("payload serialization failed", e);
        }
    }
}
