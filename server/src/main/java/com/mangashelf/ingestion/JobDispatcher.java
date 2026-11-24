package com.mangashelf.ingestion;

import com.mangashelf.config.MangaShelfProperties;
import com.mangashelf.config.RabbitTopologyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 派发器（DESIGN 11.1）：读取 PENDING 的 outbox 记录并以发布确认作为已派发依据。
 * 领取带互斥租约，确认与重试均按令牌更新；消息按 job kind 路由到对应队列。
 */
@Component
public class JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;
    /** 领取租约期限：覆盖确认超时与调度间隔余量。 */
    static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration DISPATCHED_RETENTION = Duration.ofDays(7);

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;
    private final ObjectMapper objectMapper;
    private final MangaShelfProperties props;
    private final JobMetrics metrics;

    public JobDispatcher(OutboxRepository outbox, RabbitTemplate rabbit,
                         ObjectMapper objectMapper, MangaShelfProperties props,
                         JobMetrics metrics) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.objectMapper = objectMapper;
        this.props = props;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${mangashelf.jobs.dispatch-interval-ms:500}")
    public void dispatchPending() {
        dispatchBatch(CLAIM_LEASE);
    }

    /** 允许集成测试以较短租约触发派发批次。 */
    public int dispatchBatch(Duration lease) {
        List<OutboxRepository.OutboxEvent> batch = outbox.claimBatch(50, lease);
        for (OutboxRepository.OutboxEvent event : batch) {
            dispatch(event);
        }
        return batch.size();
    }

    /** 定期清理已派发的旧历史记录，控制表体积。 */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 600_000L)
    public void cleanupDispatched() {
        int deleted = outbox.deleteDispatchedOlderThan(DISPATCHED_RETENTION);
        if (deleted > 0) {
            log.info("cleaned up {} dispatched outbox events older than {}", deleted, DISPATCHED_RETENTION);
        }
    }

    void dispatch(OutboxRepository.OutboxEvent event) {
        long startNs = System.nanoTime();
        try {
            JobMessage message = objectMapper.readValue(event.payload(), JobMessage.class);
            CorrelationData correlation = new CorrelationData(String.valueOf(event.id()));
            rabbit.convertAndSend(RabbitTopologyConfig.JOBS_EXCHANGE,
                    queueForKind(message.kind()), message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            metrics.recordOutboxConfirm(Duration.ofNanos(System.nanoTime() - startNs));
            if (confirm != null && confirm.isAck()) {
                boolean updated = outbox.markDispatched(event.id(), event.leaseToken());
                metrics.recordOutboxDispatch(updated ? "ack" : "stale");
            } else {
                log.warn("outbox {} nacked by broker, will retry", event.id());
                outbox.markRetry(event.id(), event.leaseToken(), props.jobs().maxDispatchAttempts());
                metrics.recordOutboxDispatch("nack");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outbox.markRetry(event.id(), event.leaseToken(), props.jobs().maxDispatchAttempts());
            metrics.recordOutboxDispatch("error");
        } catch (Exception e) {
            log.warn("outbox {} dispatch failed: {}", event.id(), e.getMessage());
            outbox.markRetry(event.id(), event.leaseToken(), props.jobs().maxDispatchAttempts());
            metrics.recordOutboxDispatch("error");
        }
    }

    /** kind → 队列静态映射；未知 kind 落处理队列并告警。 */
    static String queueForKind(String kind) {
        if (Job.KIND_EXTERNAL_RESOLVE.equals(kind) || "manga.plan".equals(kind)) {
            return RabbitTopologyConfig.QUEUE_CONTENT_RESOLVE;
        }
        if (Job.KIND_INDEX_BUILD.equals(kind)) {
            return RabbitTopologyConfig.QUEUE_CONTENT_INDEX;
        }
        if (!Job.KIND_CHAPTER_PARSE.equals(kind) && !Job.KIND_VERIFY.equals(kind)
                && !Job.KIND_ANIMATION_VERIFY.equals(kind) && !Job.KIND_CATALOG_SYNC.equals(kind)
                && !Job.KIND_BANGUMI_SYNC.equals(kind) && !Job.KIND_MANGA_CATALOG_SYNC.equals(kind)) {
            log.warn("unknown job kind '{}' routed to default process queue", kind);
        }
        return RabbitTopologyConfig.QUEUE_CONTENT_PROCESS;
    }
}
