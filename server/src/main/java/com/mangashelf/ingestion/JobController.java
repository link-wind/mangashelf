package com.mangashelf.ingestion;

import com.mangashelf.config.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 任务状态快照与事件（DESIGN 16.1 任务）；SSE 在 M1 提供，M0 以 JSON 轮询。 */
@RestController
@RequestMapping("/api/v1")
public class JobController {

    private final JobRepository jobs;

    public JobController(JobRepository jobs) {
        this.jobs = jobs;
    }

    public record JobSnapshot(UUID id, String kind, String status, String stage, Integer progress,
                              int attempt, String errorCode, String errorMessage) {
    }

    @GetMapping("/jobs/{id}")
    public JobSnapshot get(@PathVariable UUID id) {
        Job job = jobs.findById(id).orElseThrow(() ->
                new ApiException(404, "JOB_NOT_FOUND", "任务不存在。"));
        return new JobSnapshot(job.id(), job.kind(), job.status(), job.stage(), job.progress(),
                job.attempt(), job.errorCode(), job.errorMessage());
    }

    @GetMapping("/jobs/{id}/events")
    public List<JobEvent> events(@PathVariable UUID id) {
        jobs.findById(id).orElseThrow(() ->
                new ApiException(404, "JOB_NOT_FOUND", "任务不存在。"));
        return jobs.listEvents(id);
    }
}
