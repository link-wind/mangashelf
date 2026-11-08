package com.mangashelf.ingestion;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * 任务事件 SSE（DESIGN 11.4 / implement-manga-reading D8）：
 * 只推送数据库 job_events，事件 ID 为任务内序号，支持 Last-Event-ID / after_sequence 补读；
 * 终态推送 done 后关闭。轮询读库，慢连接不阻塞 Worker；断线由客户端带游标重连，
 * 不重放已确认的执行。虚拟线程承载阻塞轮询。
 */
@Component
public class JobEventsStream {

    private static final Logger log = LoggerFactory.getLogger(JobEventsStream.class);
    private static final long POLL_INTERVAL_MS = 600;

    private final JobRepository jobs;
    private final ObjectMapper objectMapper;

    public JobEventsStream(JobRepository jobs, ObjectMapper objectMapper) {
        this.jobs = jobs;
        this.objectMapper = objectMapper;
    }

    public SseEmitter stream(UUID jobId, long afterSequence) {
        SseEmitter emitter = new SseEmitter(0L);
        Executors.newVirtualThreadPerTaskExecutor().execute(() -> runLoop(emitter, jobId, afterSequence));
        return emitter;
    }

    private void runLoop(SseEmitter emitter, UUID jobId, long afterSequence) {
        long cursor = afterSequence;
        try {
            emitter.send(SseEmitter.event().name("open")
                    .data("{\"job_id\":\"" + jobId + "\"}"));
            while (true) {
                List<JobEvent> events = jobs.listEventsAfter(jobId, cursor);
                for (JobEvent event : events) {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(event.sequence()))
                            .name(event.type())
                            .data(json(event)));
                    cursor = event.sequence();
                }
                Optional<Job> job = jobs.findById(jobId);
                if (job.isEmpty()) {
                    emitter.complete();
                    return;
                }
                if (isTerminal(job.get().status())) {
                    emitter.send(SseEmitter.event().name("done")
                            .data("{\"status\":\"" + job.get().status() + "\"}"));
                    emitter.complete();
                    return;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (JacksonException e) {
            log.warn("job events serialization failed for {}", jobId, e);
            emitter.completeWithError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (Exception e) {
            // 客户端断开等 IO 异常：静默完成，客户端按游标重连
            log.debug("job events stream closed for {}: {}", jobId, String.valueOf(e.getMessage()));
            emitter.complete();
        }
    }

    private static boolean isTerminal(String status) {
        return Job.STATUS_SUCCEEDED.equals(status) || Job.STATUS_FAILED.equals(status)
                || Job.STATUS_CANCELLED.equals(status);
    }

    private String json(JobEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException e) {
            throw new IllegalStateException("event serialization failed", e);
        }
    }
}
