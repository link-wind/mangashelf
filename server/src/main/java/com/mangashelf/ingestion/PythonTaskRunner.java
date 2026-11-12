package com.mangashelf.ingestion;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import com.mangashelf.config.MangaShelfProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
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
        run(workDir, spec, onProgress, props.processing().taskTimeoutSeconds());
    }

    public void run(Path workDir, TaskSpec spec, java.util.function.Consumer<ProgressLine> onProgress,
                    int timeoutSeconds) throws IOException, InterruptedException {
        Path taskJson = workDir.resolve("task.json").toAbsolutePath();
        objectMapper.writeValue(taskJson.toFile(), spec);

        Path packageRoot = Path.of(props.processing().packageRoot()).toAbsolutePath().normalize();
        ProcessBuilder pb = new ProcessBuilder(
                props.processing().pythonBinary(), "-m", "processing.run",
                "--task", taskJson.toString());
        pb.directory(workDir.toFile());
        // package-root 指向包含 processing 包的目录（仓库根），Python 不感知宿主其余路径
        Map<String, String> env = pb.environment();
        env.clear();
        for (String name : java.util.List.of("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR",
                "PLAYWRIGHT_BROWSERS_PATH", "MANGASHELF_BROWSER_PROXY")) {
            String value = System.getenv(name);
            if (value != null) env.put(name, value);
        }
        env.put("PYTHONPATH", packageRoot.toString());
        env.put("PYTHONUNBUFFERED", "1");
        pb.redirectError(workDir.resolve("python.stderr.log").toFile());

        log.info("starting python task {} for job {}", spec.task(), spec.jobId());
        Process process = pb.start();
        FutureTask<Void> reader = new FutureTask<>(() -> {
            try (BufferedReader stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stdout.readLine()) != null) {
                    dispatchProgress(line, onProgress);
                }
            }
            return null;
        });
        Thread.startVirtualThread(reader);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            reader.get(timeoutSeconds, TimeUnit.SECONDS);
            if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                throw new java.util.concurrent.TimeoutException();
            }
            if (process.exitValue() != 0) {
                throw new IOException("Python 任务退出码 " + process.exitValue()
                        + "，日志见 " + workDir.resolve("python.stderr.log"));
            }
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("Python 任务超过 " + timeoutSeconds + " 秒", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("读取 Python 任务进度失败", e.getCause());
        } finally {
            if (process.isAlive()) {
                process.descendants().toList().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
            reader.cancel(true);
        }
    }

    /** stdout 只允许 JSONL（规格要求）；无法解析即协议违约，任务失败。 */
    private void dispatchProgress(String line, java.util.function.Consumer<ProgressLine> onProgress)
            throws IOException {
        try {
            var node = objectMapper.readTree(line);
            String type = node.path("type").asText("progress");
            String stage = node.path("stage").asText(null);
            String message = node.path("message").asText(null);
            if (message != null && message.startsWith("{") && message.endsWith("}")) {
                try {
                    JsonNode event = objectMapper.readTree(message);
                    stage = event.path("event").asText(stage);
                } catch (JacksonException ignored) {
                    // 普通进度文本仍按原协议传递
                }
            }
            onProgress.accept(new ProgressLine(type, stage, message));
        } catch (JacksonException e) {
            // Jackson 3 的解析异常是 unchecked；协议违约统一转任务失败
            throw new IOException("python stdout 输出不符合 JSONL 协议：" + truncate(line), e);
        }
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
