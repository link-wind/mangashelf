package com.mangashelf.external;

import com.mangashelf.ingestion.Job;
import com.mangashelf.ingestion.JobContext;
import com.mangashelf.ingestion.JobHandler;
import com.mangashelf.ingestion.JobResult;
import com.mangashelf.ingestion.PythonTaskRunner;
import com.mangashelf.ingestion.TaskSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * external.resolve：无头浏览器嗅探外部剧集页视频直链（M7 design.md D5）。
 * 结果落 external_episodes.resolved_url（领域状态在集上行，design.md D2）。
 */
@Component
public class ExternalResolveHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ExternalResolveHandler.class);
    private static final int SNIFF_TIMEOUT_SECONDS = 30;

    private final ExternalRepository externals;
    private final PythonTaskRunner python;
    private final ObjectMapper objectMapper;

    public ExternalResolveHandler(ExternalRepository externals, PythonTaskRunner python,
                                  ObjectMapper objectMapper) {
        this.externals = externals;
        this.python = python;
        this.objectMapper = objectMapper;
    }

    @Override
    public String kind() {
        return Job.KIND_EXTERNAL_RESOLVE;
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        Job job = ctx.job();
        ExternalEpisode episode = externals.findEpisode(job.externalEpisodeId()).orElse(null);
        if (episode == null) {
            return JobResult.failure("EPISODE_NOT_FOUND", "外部集不存在");
        }

        String cookie = externals.findSource(episode.sourceId())
                .map(ExternalSource::cookie).orElse("");
        var params = new java.util.HashMap<String, Object>();
        params.put("episode_url", episode.episodeUrl());
        params.put("timeout_seconds", SNIFF_TIMEOUT_SECONDS);
        if (cookie != null && !cookie.isBlank()) {
            params.put("cookie", cookie);
        }
        TaskSpec spec = new TaskSpec(TaskSpec.SCHEMA_VERSION, job.id(), ctx.attempt(),
                "external.resolve", List.of(), "output", null, params);
        Files.createDirectories(ctx.workDir());
        python.run(ctx.workDir(), spec, line -> ctx.progress().emit(line.stage(), line.message()));

        JsonNode result = objectMapper.readTree(Files.readString(ctx.workDir().resolve("result.json")));
        if (!"SUCCEEDED".equals(result.path("status").asText())) {
            String code = result.path("error_code").asText("RESOLVE_FAILED");
            String message = result.path("message").asText("视频直链解析失败");
            log.info("job {} external resolve failed: {} - {}", job.id(), code, message);
            return JobResult.failure(code, message);
        }
        String playUrl = result.path("play_url").asText();
        String playKind = result.path("play_kind").asText("hls");
        externals.markResolved(episode.id(), playUrl, playKind);
        ctx.progress().emit("resolve", "已获得播放直链");
        log.info("job {} resolved episode {}: {} ({})", job.id(), episode.id(), playKind, mask(playUrl));
        return JobResult.success("resolved");
    }

    /** 日志不携带带 token 的完整上游 URL（DESIGN 19）。 */
    private static String mask(String url) {
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) + "?…" : url;
    }
}
