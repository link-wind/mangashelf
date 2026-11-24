package com.mangashelf.ingestion;

import com.mangashelf.config.MangaShelfProperties;
import com.mangashelf.config.RabbitTopologyConfig;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * 真实 PostgreSQL + RabbitMQ 故障注入测试：
 * 覆盖 Outbox 同事务写入、租约互斥防重领、崩溃后租约回收、Broker 故障恢复、
 * Confirm 后崩溃重复投递幂等、以及毒消息路由至 DLQ。
 */
class OutboxReliabilityIT extends ReliabilityContainersBase {

    @Autowired
    private JobCreationService jobCreation;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private JobDispatcher dispatcher;

    @Autowired
    private JobMetrics metrics;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MangaShelfProperties props;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void cleanQueuesAndPendingOutbox() {
        amqpAdmin.purgeQueue(RabbitTopologyConfig.QUEUE_CONTENT_PROCESS, false);
        amqpAdmin.purgeQueue(RabbitTopologyConfig.QUEUE_CONTENT_RESOLVE, false);
        amqpAdmin.purgeQueue(RabbitTopologyConfig.QUEUE_CONTENT_INDEX, false);
        amqpAdmin.purgeQueue(RabbitTopologyConfig.QUEUE_CONTENT_DLQ, false);
        jdbc.update("DELETE FROM outbox_events");
    }

    @Test
    void transactionalOutboxRecoversAfterBrokerFailureAndTracksMetrics() throws Exception {
        UUID ownerId = createTestUser();
        UUID sessionId = createUploadSession(ownerId);
        UUID jobId = jobCreation.create(Job.KIND_VERIFY, ownerId, sessionId,
                null, null, null, null, null, null, "outbox-recover-" + sessionId);

        assertEquals(1L, outbox.countPending());

        // 故障注入：模拟首次派发时 RabbitMQ 不可达，验证记录进入退避重试而非丢失
        RabbitTemplate brokenRabbit = mock(RabbitTemplate.class);
        doThrow(new RuntimeException("simulated broker down"))
                .when(brokenRabbit).convertAndSend(anyString(), anyString(), any(Object.class),
                        any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        JobDispatcher failingDispatcher = new JobDispatcher(outbox, brokenRabbit, objectMapper, props, metrics);

        double errorBefore = counter("mangashelf.outbox.dispatch.total", "result", "error");
        int claimed = failingDispatcher.dispatchBatch(Duration.ofSeconds(30));
        assertEquals(1, claimed);
        assertEquals(errorBefore + 1.0, counter("mangashelf.outbox.dispatch.total", "result", "error"));

        // 状态仍为 PENDING 且 attempts=1，lease_token 已释放
        Integer attempts = jdbc.queryForObject(
                "SELECT attempts FROM outbox_events WHERE job_id = ?", Integer.class, jobId);
        String status = jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE job_id = ?", String.class, jobId);
        assertEquals(1, attempts);
        assertEquals("PENDING", status);

        // 恢复：将退避时间拉回当前并由正常 dispatcher 派发，消息到达真实 RabbitMQ 队列
        jdbc.update("UPDATE outbox_events SET next_attempt_at = now() - interval '1 second' WHERE job_id = ?", jobId);
        double ackBefore = counter("mangashelf.outbox.dispatch.total", "result", "ack");
        assertEquals(1, dispatcher.dispatchBatch(Duration.ofSeconds(30)));
        assertEquals(ackBefore + 1.0, counter("mangashelf.outbox.dispatch.total", "result", "ack"));
        assertEquals(0L, outbox.countPending());

        Message received = rabbit.receive(RabbitTopologyConfig.QUEUE_CONTENT_PROCESS, 3000);
        assertNotNull(received, "恢复后消息应到达 content.process");
        JobMessage parsed = objectMapper.readValue(
                new String(received.getBody(), StandardCharsets.UTF_8), JobMessage.class);
        assertEquals(jobId, parsed.jobId());
        assertEquals(Job.KIND_VERIFY, parsed.kind());
    }

    @Test
    void unexpiredOutboxLeaseBlocksConcurrentClaimAndExpiredLeaseRecovers() {
        UUID ownerId = createTestUser();
        UUID sessionId = createUploadSession(ownerId);
        UUID jobId = jobCreation.create(Job.KIND_VERIFY, ownerId, sessionId,
                null, null, null, null, null, null, "outbox-lease-" + sessionId);

        // 实例 A 领取租约（30 秒有效）
        List<OutboxRepository.OutboxEvent> batchA = outbox.claimBatch(10, Duration.ofSeconds(30));
        assertEquals(1, batchA.size());
        OutboxRepository.OutboxEvent eventA = batchA.getFirst();
        assertNotNull(eventA.leaseToken());

        // 实例 B 在租约未过期时调用 claimBatch：必须被互斥挡住，不重复领取、不覆写 lease_token
        List<OutboxRepository.OutboxEvent> batchB = outbox.claimBatch(10, Duration.ofSeconds(30));
        assertTrue(batchB.isEmpty(), "未过期租约的 PENDING 记录不得被另一实例重复领取");

        // 模拟实例 A 崩溃，租约超时过期
        jdbc.update("UPDATE outbox_events SET lease_until = now() - interval '5 seconds' WHERE id = ?", eventA.id());

        // 实例 B 成功回收并分配新 lease_token
        List<OutboxRepository.OutboxEvent> recovered = outbox.claimBatch(10, Duration.ofSeconds(30));
        assertEquals(1, recovered.size());
        OutboxRepository.OutboxEvent eventB = recovered.getFirst();
        assertNotEquals(eventA.leaseToken(), eventB.leaseToken());

        // 实例 A 延迟恢复后尝试用旧令牌确认或标记重试：必须均被拒绝
        assertFalse(outbox.markDispatched(eventA.id(), eventA.leaseToken()));
        assertFalse(outbox.markRetry(eventA.id(), eventA.leaseToken(), 5));

        // 实例 B 正常确认成功
        assertTrue(outbox.markDispatched(eventB.id(), eventB.leaseToken()));
        assertEquals("DISPATCHED", jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE job_id = ?", String.class, jobId));
    }

    @Test
    void duplicateDeliveryAfterConfirmCrashIsIdempotent() {
        UUID ownerId = createTestUser();
        UUID sessionId = createUploadSession(ownerId);
        UUID jobId = jobCreation.create(Job.KIND_VERIFY, ownerId, sessionId,
                null, null, null, null, null, null, "dup-delivery-" + sessionId);

        AtomicInteger sideEffectCount = new AtomicInteger(0);
        JobHandler countingHandler = new JobHandler() {
            @Override public String kind() { return Job.KIND_VERIFY; }
            @Override public JobResult run(JobContext ctx) {
                sideEffectCount.incrementAndGet();
                return JobResult.success("verified");
            }
        };
        JobExecutor testExecutor = new JobExecutor(
                jobs, outbox, objectMapper, props, tx, metrics, List.of(countingHandler));

        JobMessage msg = new JobMessage(JobMessage.SCHEMA_VERSION, jobId, 1, Job.KIND_VERIFY);
        // 模拟 Confirm 后 Publisher 崩溃未标 DISPATCHED，导致同一消息被投递两次
        testExecutor.execute(msg);
        testExecutor.execute(msg);

        Job finalJob = jobs.findById(jobId).orElseThrow();
        assertEquals(Job.STATUS_SUCCEEDED, finalJob.status());
        assertEquals(1, finalJob.attempt(), "重复投递不得重复执行任务");
        assertEquals(1, sideEffectCount.get(), "重复投递不得重复产生业务副作用");
    }

    @Test
    void poisonMessageRoutesToDlqWithoutBlockingValidMessages() throws Exception {
        UUID ownerId = createTestUser();
        UUID sessionId = createUploadSession(ownerId);
        UUID jobId = jobCreation.create(Job.KIND_VERIFY, ownerId, sessionId,
                null, null, null, null, null, null, "poison-dlq-" + sessionId);

        AtomicInteger executed = new AtomicInteger(0);
        JobHandler okHandler = new JobHandler() {
            @Override public String kind() { return Job.KIND_VERIFY; }
            @Override public JobResult run(JobContext ctx) {
                executed.incrementAndGet();
                return JobResult.success("ok");
            }
        };
        JobExecutor testExecutor = new JobExecutor(
                jobs, outbox, objectMapper, props, tx, metrics, List.of(okHandler));
        JobConsumer testConsumer = new JobConsumer(testExecutor, objectMapper, metrics);

        // 先投递一条损坏 JSON（毒消息），再投递一条合法消息到 content.process
        rabbit.send(RabbitTopologyConfig.JOBS_EXCHANGE, RabbitTopologyConfig.QUEUE_CONTENT_PROCESS,
                new Message("{not-valid-json".getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        String validJson = objectMapper.writeValueAsString(
                new JobMessage(JobMessage.SCHEMA_VERSION, jobId, 1, Job.KIND_VERIFY));
        rabbit.send(RabbitTopologyConfig.JOBS_EXCHANGE, RabbitTopologyConfig.QUEUE_CONTENT_PROCESS,
                new Message(validJson.getBytes(StandardCharsets.UTF_8), new MessageProperties()));

        double dlqBefore = counter("mangashelf.jobs.dlq.routed");
        try (Connection conn = connectionFactory.createConnection();
             Channel channel = conn.createChannel(false)) {
            // 消费第 1 条（毒消息）：应被 basicNack(requeue=false) 送入 content.dlq
            var resp1 = channel.basicGet(RabbitTopologyConfig.QUEUE_CONTENT_PROCESS, false);
            assertNotNull(resp1);
            MessageProperties props1 = new MessageProperties();
            props1.setDeliveryTag(resp1.getEnvelope().getDeliveryTag());
            testConsumer.consume(new Message(resp1.getBody(), props1), channel);

            // 消费第 2 条（合法消息）：应正常处理并 ACK，不被毒消息阻塞
            var resp2 = channel.basicGet(RabbitTopologyConfig.QUEUE_CONTENT_PROCESS, false);
            assertNotNull(resp2);
            MessageProperties props2 = new MessageProperties();
            props2.setDeliveryTag(resp2.getEnvelope().getDeliveryTag());
            testConsumer.consume(new Message(resp2.getBody(), props2), channel);
        }

        assertEquals(dlqBefore + 1.0, counter("mangashelf.jobs.dlq.routed"));
        assertEquals(1, executed.get());
        assertEquals(Job.STATUS_SUCCEEDED, jobs.findById(jobId).orElseThrow().status());

        Message deadLetter = rabbit.receive(RabbitTopologyConfig.QUEUE_CONTENT_DLQ, 3000);
        assertNotNull(deadLetter, "毒消息应被 RabbitMQ 死信交换机路由到 content.dlq");
        assertEquals("{not-valid-json", new String(deadLetter.getBody(), StandardCharsets.UTF_8));
    }

    private double counter(String name, String... tags) {
        var c = meterRegistry.find(name).tags(tags).counter();
        return c == null ? 0.0 : c.count();
    }
}
