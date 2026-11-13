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
 * Worker 消费者（DESIGN 11.2）：结果与事件入库后确认消息。
 * 三队列显式监听（content.resolve/process/index）——曾尝试按配置动态注册容器，
 * 但 List 形式的 @Bean 不受 Spring 生命周期管理导致容器不启动，回归为显式监听；
 * 按队列拆分 Worker 角色留到有真实部署需求时再做（distributed-evolution 9.7）。
 * 任务执行失败（已提交 FAILED）与重复通知同样确认；未预期异常按"重投无益"记录并确认，
 * 避免无限重投，真实中断恢复由租约恢复扫描补齐。
 */
@Component
@Profile("worker")
public class JobConsumer {

    private static final Logger log = LoggerFactory.getLogger(JobConsumer.class);

    private final JobExecutor executor;
    private final ObjectMapper objectMapper;

    public JobConsumer(JobExecutor executor, ObjectMapper objectMapper) {
        this.executor = executor;
        this.objectMapper = objectMapper;
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
        try {
            JobMessage msg = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), JobMessage.class);
            executor.execute(msg);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("job message processing failed, ack to avoid redelivery loop", e);
            channel.basicAck(tag, false);
        }
    }
}
