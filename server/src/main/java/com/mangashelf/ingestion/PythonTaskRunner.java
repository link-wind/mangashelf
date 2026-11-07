package com.mangashelf.ingestion;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.mangashelf.config.MangaShelfProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 固定 Python 任务入口（DESIGN 12.2）：
 * python -m processing.run --task <task.json>；stdout 仅 JSONL 进度，stderr 写日志文件。
 * Python 不持有数据库与对象存储凭据；输入由 Java 预先下载到任务目录。
 */
@Component
public class PythonTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(PythonTaskRunner.class);

    public record ProgressLine(String type, String stage, String message) {
    }

    private final MangaShelfProperties props;
    private final ObjectMapper objectMapper;

    public PythonTaskRunner(MangaShelfProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 运行任务并回传解析后的进度行；exit code 非 0、超时或 stdout 出现非 JSONL 内容都视为失败。
     */
    public void run(Path workDir, TaskSpec spec, java.util.function.Consumer<ProgressLine> onProgress)
            throws IOException, InterruptedException {
        Path taskJson = workDir.resolve("task.json").toAbsolutePath();
        objectMapper.writeValue(taskJson.toFile(), spec);

        Path packageRoot = Path.of(props.processing().packageRoot()).toAbsolutePath().normalize();
        ProcessBuilder pb = new ProcessBuilder(
                props.processing().pythonBinary(), "-m", "processing.run",
                "--task", taskJson.toString());
        pb.directory(workDir.toFile());
        pb.environment().put("PYTHONPATH", packageRoot.toString());
        pb.environment().put("PYTHONUNBUFFERED", "1");
        pb.redirectError(workDir.resolve("python.stderr.log").toFile());

        log.info("starting python task {} for job {}", spec.task(), spec.jobId());
        Process process = pb.start();
        try (BufferedReader stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = stdout.readLine()) != null) {
                dispatchProgress(line, onProgress);
            }
        }
        if (!process.waitFor(props.processing().taskTimeoutSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("python task timed out after "
                    + props.processing().taskTimeoutSeconds() + "s");
        }
        int exit = process.exitValue();
        if (exit != 0) {
            throw new IOException("python task exited with code " + exit
                    + "，日志见 " + workDir.resolve("python.stderr.log"));
        }
    }

    /** stdout 只允许 JSONL（规格要求）；无法解析即协议违约，任务失败。 */
    private void dispatchProgress(String line, java.util.function.Consumer<ProgressLine> onProgress)
            throws IOException {
        try {
            var node = objectMapper.readTree(line);
            String type = node.path("type").asText("progress");
            onProgress.accept(new ProgressLine(type,
                    node.path("stage").asText(null), node.path("message").asText(null)));
        } catch (JacksonException e) {
            // Jackson 3 的解析异常是 unchecked；协议违约统一转任务失败
            throw new IOException("python stdout 输出不符合 JSONL 协议：" + truncate(line), e);
        }
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
