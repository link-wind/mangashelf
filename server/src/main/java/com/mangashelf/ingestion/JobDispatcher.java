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
 * API 与 Worker 角色都可运行；重复消息始终被允许，只以 broker 确认标记 DISPATCHED。
 */
@Component
public class JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

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
        List<OutboxRepository.OutboxEvent> batch = outbox.claimBatch(50);
        for (OutboxRepository.OutboxEvent event : batch) {
            dispatch(event);
        }
    }

    private void dispatch(OutboxRepository.OutboxEvent event) {
        try {
            JobMessage message = objectMapper.readValue(event.payload(), JobMessage.class);
            CorrelationData correlation = new CorrelationData(String.valueOf(event.id()));
            rabbit.convertAndSend(RabbitTopologyConfig.JOBS_EXCHANGE,
                    RabbitTopologyConfig.QUEUE_CONTENT_PROCESS, message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (confirm != null && confirm.isAck()) {
                outbox.markDispatched(event.id());
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
}
