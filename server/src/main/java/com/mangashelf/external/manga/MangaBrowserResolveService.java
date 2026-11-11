package com.mangashelf.external.manga;

import com.mangashelf.config.MangaShelfProperties;
import com.mangashelf.ingestion.PythonTaskRunner;
import com.mangashelf.ingestion.TaskSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 漫画浏览器解析（M29）：复用动画 Worker 的「无头浏览器 + 固定 Python 任务」模式。
 *
 * <p>拷贝漫画章节图由网页 JS 解密（游客 contentKey 为空 → 0 页），纯 HTTP 抓不到；
 * 这里在本机/Worker 用 Playwright 打开真实页面执行 JS，返回页图 URL 列表。结果
 * 缓存 10 分钟（章节）/ 30 分钟（详情），浏览器启动串行化（单并发）。</p>
 */
@Service
public class MangaBrowserResolveService {

    private static final Logger log = LoggerFactory.getLogger(MangaBrowserResolveService.class);
    private static final long CHAPTER_TTL_MS = 10 * 60 * 1000L;
    private static final long DETAIL_TTL_MS = 30 * 60 * 1000L;
    private static final long SEARCH_TTL_MS = 5 * 60 * 1000L;
    private static final int TIMEOUT_SECONDS = 45;

    private record Cache(JsonNode result, long expiresAt) {}

    private final PythonTaskRunner python;
    private final ObjectMapper objectMapper;
    private final MangaShelfProperties props;
    private final Semaphore gate = new Semaphore(1, true);
    private final ConcurrentHashMap<String, Cache> cache = new ConcurrentHashMap<>();

    public MangaBrowserResolveService(PythonTaskRunner python, ObjectMapper objectMapper,
                                      MangaShelfProperties props) {
        this.python = python;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public JsonNode resolveChapter(String url, String cookie, String storageState) throws Exception {
        return resolve("chapter", url, cookie, storageState, CHAPTER_TTL_MS);
    }

    public JsonNode resolveDetail(String url, String cookie, String storageState) throws Exception {
        return resolve("detail", url, cookie, storageState, DETAIL_TTL_MS);
    }

    public JsonNode resolveSearch(String url, String cookie, String storageState) throws Exception {
        return resolve("search", url, cookie, storageState, SEARCH_TTL_MS);
    }

    /** 声明式浏览器计划（M32）：action=search/detail/pages，plan 为 browserPlan JSON。 */
    public JsonNode resolvePlan(String action, String planJson, Map<String, String> vars,
                                String cookie, String storageState) throws Exception {
        String key = "plan|" + action + "|" + planJson + "|" + vars;
        Cache hit = cache.get(key);
        if (hit != null && hit.expiresAt() > System.currentTimeMillis()) {
            return hit.result();
        }
        if (!gate.tryAcquire(90, TimeUnit.SECONDS)) {
            throw new IllegalStateException("漫画解析繁忙，请稍后重试");
        }
        try {
            JsonNode result = runPlanTask(action, planJson, vars, cookie, storageState);
            cache.put(key, new Cache(result, System.currentTimeMillis() + DETAIL_TTL_MS));
            return result;
        } finally {
            gate.release();
        }
    }

    private JsonNode runPlanTask(String action, String planJson, Map<String, String> vars,
                                 String cookie, String storageState) throws Exception {
        Path workRoot = Path.of(props.processing().workRoot()).toAbsolutePath()
                .resolve("manga-resolve");
        Path dir = workRoot.resolve(UUID.randomUUID().toString());
        Files.createDirectories(dir);
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("action", action);
            params.put("plan", planJson);
            params.put("vars", vars == null ? Map.of() : vars);
            params.put("timeout_seconds", TIMEOUT_SECONDS);
            if (cookie != null && !cookie.isBlank()) params.put("cookie", cookie);
            if (storageState != null && !storageState.isBlank()) params.put("storage_state", storageState);
            TaskSpec spec = new TaskSpec(TaskSpec.SCHEMA_VERSION, UUID.randomUUID(), 0,
                    "manga.plan", List.of(), dir.toString(),
                    new TaskSpec.Limits(0, 0, 0), params);
            python.run(dir, spec, line -> { });
            Path resultPath = dir.resolve("result.json");
            if (!Files.isRegularFile(resultPath)) {
                throw new IllegalStateException("浏览器计划未产出 result.json");
            }
            JsonNode result = objectMapper.readTree(Files.readString(resultPath));
            if (!"SUCCEEDED".equals(result.path("status").asText())) {
                throw new IllegalStateException(result.path("message").asText("浏览器解析失败"));
            }
            return result;
        } finally {
            deleteQuietly(dir);
        }
    }

    private JsonNode resolve(String mode, String url, String cookie, String storageState,
                            long ttlMs) throws Exception {
        String key = mode + "|" + url;
        Cache hit = cache.get(key);
        if (hit != null && hit.expiresAt() > System.currentTimeMillis()) {
            return hit.result();
        }
        if (!gate.tryAcquire(90, TimeUnit.SECONDS)) {
            throw new IllegalStateException("漫画解析繁忙，请稍后重试");
        }
        try {
            JsonNode result = runTask(mode, url, cookie, storageState);
            cache.put(key, new Cache(result, System.currentTimeMillis() + ttlMs));
            return result;
        } finally {
            gate.release();
        }
    }

    private JsonNode runTask(String mode, String url, String cookie, String storageState)
            throws Exception {
        Path workRoot = Path.of(props.processing().workRoot()).toAbsolutePath()
                .resolve("manga-resolve");
        Path dir = workRoot.resolve(UUID.randomUUID().toString());
        Files.createDirectories(dir);
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("mode", mode);
            params.put("url", url);
            params.put("timeout_seconds", TIMEOUT_SECONDS);
            if (cookie != null && !cookie.isBlank()) params.put("cookie", cookie);
            if (storageState != null && !storageState.isBlank()) params.put("storage_state", storageState);

            TaskSpec spec = new TaskSpec(TaskSpec.SCHEMA_VERSION, UUID.randomUUID(), 0,
                    "manga.resolve", List.of(), dir.toString(),
                    new TaskSpec.Limits(0, 0, 0), params);
            python.run(dir, spec, line -> { });

            Path resultPath = dir.resolve("result.json");
            if (!Files.isRegularFile(resultPath)) {
                throw new IllegalStateException("漫画解析未产出 result.json");
            }
            JsonNode result = objectMapper.readTree(Files.readString(resultPath));
            if (!"SUCCEEDED".equals(result.path("status").asText())) {
                String message = result.path("message").asText("漫画解析失败");
                log.warn("manga resolve failed {} {}: {}", mode, url, message);
                throw new IllegalStateException(message);
            }
            return result;
        } finally {
            // task.json 含登录 Cookie，解析完即删，不在磁盘留凭据
            deleteQuietly(dir);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理失败不影响解析结果
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }
}
