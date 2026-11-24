package com.mangashelf.ingestion;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 异步任务与 Outbox 核心指标（harden-reliable-jobs-and-fault-injection）：
 * 复用现有 Actuator Prometheus 暴露端点，不引入额外监控基础设施。
 */
@Component
public class JobMetrics {

    private final MeterRegistry registry;
    private final Timer outboxConfirmTimer;
    private final Counter leaseRecoveredCounter;
    private final Counter staleLeaseRejectedCounter;
    private final Counter dlqRoutedCounter;

    public JobMetrics(MeterRegistry registry, OutboxRepository outbox) {
        this.registry = registry;
        this.outboxConfirmTimer = Timer.builder("mangashelf.outbox.confirm.duration")
                .description("RabbitMQ publisher confirm latency for outbox dispatch")
                .register(registry);
        this.leaseRecoveredCounter = Counter.builder("mangashelf.jobs.lease.recovered")
                .description("Expired job leases recovered and re-dispatched")
                .register(registry);
        this.staleLeaseRejectedCounter = Counter.builder("mangashelf.jobs.lease.stale_rejected")
                .description("Stale worker commits or writes rejected by lease fencing")
                .register(registry);
        this.dlqRoutedCounter = Counter.builder("mangashelf.jobs.dlq.routed")
                .description("Poison messages routed to content.dlq")
                .register(registry);

        Gauge.builder("mangashelf.outbox.pending", outbox, OutboxRepository::countPending)
                .description("Current count of PENDING outbox events")
                .register(registry);
        Gauge.builder("mangashelf.outbox.oldest_pending_seconds", outbox, OutboxRepository::oldestPendingAgeSeconds)
                .description("Age in seconds of the oldest PENDING outbox event")
                .register(registry);
    }

    public void recordOutboxDispatch(String result) {
        Counter.builder("mangashelf.outbox.dispatch.total")
                .tag("result", result)
                .register(registry)
                .increment();
    }

    public void recordOutboxConfirm(Duration duration) {
        outboxConfirmTimer.record(duration);
    }

    public void recordJobOutcome(String kind, String outcome, Duration duration) {
        String safeKind = kind == null ? "unknown" : kind;
        Counter.builder("mangashelf.jobs.total")
                .tag("kind", safeKind)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        if (duration != null) {
            Timer.builder("mangashelf.jobs.duration")
                    .tag("kind", safeKind)
                    .tag("outcome", outcome)
                    .register(registry)
                    .record(duration);
        }
    }

    public void recordLeaseRecovered() {
        leaseRecoveredCounter.increment();
    }

    public void recordStaleLeaseRejected() {
        staleLeaseRejectedCounter.increment();
    }

    public void recordDlqRouted() {
        dlqRoutedCounter.increment();
    }
}
