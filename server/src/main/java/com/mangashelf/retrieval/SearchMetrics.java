package com.mangashelf.retrieval;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 检索可观测指标（benchmark-and-optimize-hybrid-search）：
 * 记录检索总耗时、Embedding 推理耗时与请求分布。
 */
@Component
public class SearchMetrics {

    private final MeterRegistry registry;

    public SearchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSearch(String mode, String strategy, Duration duration) {
        String safeMode = mode == null ? "unknown" : mode;
        String safeStrategy = strategy == null ? "unknown" : strategy;
        Counter.builder("mangashelf.search.requests.total")
                .tag("mode", safeMode)
                .tag("strategy", safeStrategy)
                .register(registry)
                .increment();
        Timer.builder("mangashelf.search.duration")
                .tag("mode", safeMode)
                .tag("strategy", safeStrategy)
                .register(registry)
                .record(duration);
    }

    public void recordEmbed(String phase, Duration duration) {
        Timer.builder("mangashelf.search.embed.duration")
                .tag("phase", phase == null ? "query" : phase)
                .register(registry)
                .record(duration);
    }
}
