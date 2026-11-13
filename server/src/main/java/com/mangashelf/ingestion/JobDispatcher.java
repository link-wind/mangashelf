package com.mangashelf.ingestion;

import com.mangashelf.config.MangaShelfProperties;
import com.mangashelf.config.RabbitTopologyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 派发器（DESIGN 11.1）：读取 PENDING 的 outbox 记录并以发布确认作为已派发依据。
 * 领取带租约（harden-job-lease-and-routing），确认按令牌更新；
 * 消息按 job kind 路由到对应队列，未知 kind 落处理队列并告警。
 */
@Component
public class JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;
    /** 领取租约期限：覆盖确认超时与调度间隔余量。 */
    private static final java.time.Duration CLAIM_LEASE = java.time.Duration.ofSeconds(30);

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;
    private final ObjectMapper objectMapper;
    private final MangaShelfProperties props;

    public JobDispatcher(OutboxRepository outbox, RabbitTemplate rabbit,
                         ObjectMapper objectMapper, MangaShelfProperties props) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${mangashelf.jobs.dispatch-interval-ms:500}")
    public void dispatchPending() {
        List<OutboxRepository.OutboxEvent> batch = outbox.claimBatch(50, CLAIM_LEASE);
        for (OutboxRepository.OutboxEvent event : batch) {
            dispatch(event);
        }
    }

    private void dispatch(OutboxRepository.OutboxEvent event) {
        try {
            JobMessage message = objectMapper.readValue(event.payload(), JobMessage.class);
            CorrelationData correlation = new CorrelationData(String.valueOf(event.id()));
            rabbit.convertAndSend(RabbitTopologyConfig.JOBS_EXCHANGE,
                    queueForKind(message.kind()), message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (confirm != null && confirm.isAck()) {
                outbox.markDispatched(event.id(), event.leaseToken());
            } else {
                log.warn("outbox {} nacked by broker, will retry", event.id());
                outbox.markRetry(event.id(), props.jobs().maxDispatchAttempts());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outbox.markRetry(event.id(), props.jobs().maxDispatchAttempts());
        } catch (Exception e) {
            log.warn("outbox {} dispatch failed: {}", event.id(), e.getMessage());
            outbox.markRetry(event.id(), props.jobs().maxDispatchAttempts());
        }
    }

    /** kind → 队列静态映射（design 决策 5）；未知 kind 落处理队列并告警。 */
    private static String queueForKind(String kind) {
        if (Job.KIND_EXTERNAL_RESOLVE.equals(kind) || "manga.plan".equals(kind)) {
            return RabbitTopologyConfig.QUEUE_CONTENT_RESOLVE;
        }
        if (Job.KIND_INDEX_BUILD.equals(kind)) {
            return RabbitTopologyConfig.QUEUE_CONTENT_INDEX;
        }
        if (!Job.KIND_CHAPTER_PARSE.equals(kind) && !Job.KIND_VERIFY.equals(kind)
                && !Job.KIND_ANIMATION_VERIFY.equals(kind) && !Job.KIND_CATALOG_SYNC.equals(kind)
                && !Job.KIND_BANGUMI_SYNC.equals(kind)) {
            log.warn("unknown job kind '{}' routed to default process queue", kind);
        }
        return RabbitTopologyConfig.QUEUE_CONTENT_PROCESS;
    }
}
