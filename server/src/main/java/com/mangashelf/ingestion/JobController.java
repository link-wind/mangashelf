package com.mangashelf.ingestion;

import com.mangashelf.config.ApiException;
import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * 任务端点（DESIGN 16.1 任务 / 11.4）：状态快照、事件 SSE（Last-Event-ID 补读）、
 * 显式取消与重试。取消在 Worker 阶段边界生效；重试仅对 FAILED 显式开放。
 */
@RestController
@RequestMapping("/api/v1")
public class JobController {

    private final JobRepository jobs;
    private final JobCreationService jobCreation;
    private final JobEventsStream eventsStream;

    public JobController(JobRepository jobs, JobCreationService jobCreation,
                         JobEventsStream eventsStream) {
        this.jobs = jobs;
        this.jobCreation = jobCreation;
        this.eventsStream = eventsStream;
    }

    public record JobSnapshot(UUID id, String kind, String status, String stage, Integer progress,
                              int attempt, String errorCode, String errorMessage) {
    }

    static JobSnapshot toSnapshot(Job job) {
        return new JobSnapshot(job.id(), job.kind(), job.status(), job.stage(), job.progress(),
                job.attempt(), job.errorCode(), job.errorMessage());
    }

    @GetMapping("/jobs/{id}")
    public JobSnapshot get(@AuthUser CurrentUser user, @PathVariable UUID id) {
        return toSnapshot(requireOwnedJob(user, id));
    }

    @GetMapping(value = "/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@AuthUser CurrentUser user, @PathVariable UUID id,
                             @RequestParam(name = "after_sequence", required = false) Long afterSequence,
                             @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        Job job = requireOwnedJob(user, id);
        long after = afterSequence != null ? afterSequence : parseLastEventId(lastEventId);
        return eventsStream.stream(job.id(), after);
    }

    @PostMapping("/jobs/{id}/cancel")
    public ResponseEntity<JobSnapshot> cancel(@AuthUser CurrentUser user, @PathVariable UUID id) {
        requireOwnedJob(user, id);
        if (!jobs.requestCancel(id, user.id())) {
            throw new ApiException(409, "INVALID_STATE", "任务当前状态不允许取消。");
        }
        return ResponseEntity.accepted().body(toSnapshot(jobs.findById(id).orElseThrow()));
    }

    @PostMapping("/jobs/{id}/retry")
    public ResponseEntity<JobSnapshot> retry(@AuthUser CurrentUser user, @PathVariable UUID id) {
        Job job = requireOwnedJob(user, id);
        if (!jobs.resetForRetry(id, user.id())) {
            throw new ApiException(409, "INVALID_STATE", "只有 FAILED 任务可以重试。");
        }
        // 幂等键命中既有任务，追加新 dispatch_no 的派发记录（DESIGN 11.3）
        jobCreation.create(job.kind(), job.ownerId(), job.uploadSessionId(),
                job.chapterRevisionId(), job.dedupKey());
        return ResponseEntity.accepted().body(toSnapshot(jobs.findById(id).orElseThrow()));
    }

    private Job requireOwnedJob(CurrentUser user, UUID id) {
        Job job = jobs.findById(id).orElseThrow(() ->
                new ApiException(404, "JOB_NOT_FOUND", "任务不存在。"));
        if (!job.ownerId().equals(user.id()) && !user.isAdmin()) {
            throw new ApiException(404, "JOB_NOT_FOUND", "任务不存在。");
        }
        return job;
    }

    private static long parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(lastEventId);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
