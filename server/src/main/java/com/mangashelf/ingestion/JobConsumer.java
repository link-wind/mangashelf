package com.mangashelf.ingestion;

import com.mangashelf.config.RabbitTopologyConfig;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Worker 消费者（DESIGN 11.2 / harden-reliable-jobs-and-fault-injection）：
 * - 损坏 JSON 或缺少 jobId 的毒消息通过 basicNack(..., requeue=false) 路由至 content.dlq 隔离；
 * - 合法消息交由 JobExecutor 按数据库租约状态机处理并 ACK，避免无限重投循环。
 */
@Component
@Profile("worker")
public class JobConsumer {

    private static final Logger log = LoggerFactory.getLogger(JobConsumer.class);

    private final JobExecutor executor;
    private final ObjectMapper objectMapper;
    private final JobMetrics metrics;

    public JobConsumer(JobExecutor executor, ObjectMapper objectMapper, JobMetrics metrics) {
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @RabbitListener(queues = RabbitTopologyConfig.QUEUE_CONTENT_RESOLVE, ackMode = "MANUAL")
    public void onContentResolveMessage(Message message, Channel channel) throws IOException {
        consume(message, channel);
    }

    @RabbitListener(queues = RabbitTopologyConfig.QUEUE_CONTENT_PROCESS, ackMode = "MANUAL")
    public void onContentProcessMessage(Message message, Channel channel) throws IOException {
        consume(message, channel);
    }

    @RabbitListener(queues = RabbitTopologyConfig.QUEUE_CONTENT_INDEX, ackMode = "MANUAL")
    public void onContentIndexMessage(Message message, Channel channel) throws IOException {
        consume(message, channel);
    }

    void consume(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        JobMessage msg;
        try {
            msg = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), JobMessage.class);
            if (msg == null || msg.jobId() == null) {
                throw new IllegalArgumentException("missing jobId in JobMessage");
            }
        } catch (Exception e) {
            log.error("poison job message detected, routing to DLQ: {}", e.getMessage());
            metrics.recordDlqRouted();
            channel.basicNack(tag, false, false);
            return;
        }
        try {
            executor.execute(msg);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("job {} unexpected dispatch error, ack to avoid redelivery loop", msg.jobId(), e);
            channel.basicAck(tag, false);
        }
    }
}
