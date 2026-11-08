package com.mangashelf.retrieval;

import com.mangashelf.catalog.CatalogRepository;
import com.mangashelf.catalog.ChapterRevision;
import com.mangashelf.catalog.Page;
import com.mangashelf.config.MangaShelfProperties;
import com.mangashelf.ingestion.Job;
import com.mangashelf.ingestion.JobContext;
import com.mangashelf.ingestion.JobHandler;
import com.mangashelf.ingestion.JobResult;
import com.mangashelf.ingestion.PythonTaskRunner;
import com.mangashelf.ingestion.TaskSpec;
import com.mangashelf.storage.AssetRepository;
import com.mangashelf.storage.StorageKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * index.build：页级 OCR 索引构建（DESIGN 15.2/15.5）。
 * RapidOCR 本地推理（无模型费用）；嵌入可选（模型可用时产出向量，V6 后生效）。
 * 批次完整落库后原子激活（旧 ACTIVE 降为 READY）。
 */
@Component
public class IndexBuildHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(IndexBuildHandler.class);
    public static final String EMBEDDING_MODEL_NAME = "e5-small-v2";

    private final RetrievalRepository retrievals;
    private final CatalogRepository catalog;
    private final AssetRepository assets;
    private final S3Client s3;
    private final MangaShelfProperties props;
    private final PythonTaskRunner python;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    public IndexBuildHandler(RetrievalRepository retrievals, CatalogRepository catalog,
                             AssetRepository assets, S3Client s3, MangaShelfProperties props,
                             PythonTaskRunner python, ObjectMapper objectMapper,
                             TransactionTemplate tx,
                             ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.retrievals = retrievals;
        this.catalog = catalog;
        this.assets = assets;
        this.s3 = s3;
        this.props = props;
        this.python = python;
        this.objectMapper = objectMapper;
        this.tx = tx;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    @Override
    public String kind() {
        return Job.KIND_INDEX_BUILD;
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        Job job = ctx.job();
        var generation = retrievals.findGeneration(job.indexGenerationId()).orElse(null);
        if (generation == null) {
            return JobResult.failure("GENERATION_NOT_FOUND", "索引批次不存在");
        }
        ChapterRevision revision = catalog.findRevision(generation.chapterRevisionId()).orElse(null);
        if (revision == null) {
            return JobResult.failure("REVISION_NOT_FOUND", "章节版本不存在");
        }
        // 来源清单摘要核对（DESIGN 15.5：索引记录来源摘要，变更须重建）
        if (!generation.sourceManifestSha256().equals(revision.manifestSha256())) {
            return JobResult.failure("SOURCE_CHANGED", "章节版本清单已变更，请重新触发索引");
        }
        List<Page> pages = catalog.listPages(revision.id(), 0, Integer.MAX_VALUE);
        if (pages.isEmpty()) {
            return JobResult.failure("NO_PAGES", "版本没有页面清单");
        }

        // ── 下载页面到任务目录（DESIGN 10.3：任务专属目录）──
        Path workDir = ctx.workDir();
        Files.createDirectories(workDir.resolve("input"));
        List<TaskSpec.TaskInput> inputs = new ArrayList<>();
        for (Page page : pages) {
            String key = assets.findObjectKey(page.imageAssetId()).orElseThrow();
            Path target = workDir.resolve("input").resolve(
                    "page-%04d.webp".formatted(page.ordinal())).toAbsolutePath().normalize();
            ctx.progress().emit("download", "下载页面 " + page.ordinal());
            try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                    .bucket(props.storage().bucket()).key(key).build())) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException("页面下载失败：" + page.ordinal(), e);
            }
            inputs.add(new TaskSpec.TaskInput("page",
                    workDir.relativize(target).toString().replace('\\', '/'), page.ordinal()));
        }

        if (ctx.cancelled()) {
            return new JobResult(Job.STATUS_CANCELLED, "cancelled", null, "CANCELLED", "任务已被所有者取消");
        }

        // ── RapidOCR 页级识别 ──
        TaskSpec spec = new TaskSpec(TaskSpec.SCHEMA_VERSION, job.id(), ctx.attempt(), "chapter.ocr",
                inputs, "output", null);
        python.run(workDir, spec, line -> ctx.progress().emit(line.stage(), line.message()));
        JsonNode result = objectMapper.readTree(Files.readString(workDir.resolve("result.json")));
        if (!"SUCCEEDED".equals(result.path("status").asText())) {
            String code = result.path("error_code").asText("OCR_FAILED");
            retrievals.markGenerationStatus(generation.id(), "FAILED", code,
                    result.path("message").asText("OCR 失败"));
            return JobResult.failure(code, result.path("message").asText("OCR 失败"));
        }

        // ── 索引块构建（可选嵌入）──
        EmbeddingModel embedding = embeddingModelProvider.getIfAvailable();
        List<RetrievalRepository.ChunkRow> chunks = new ArrayList<>();
        int dimensions = -1;
        for (JsonNode page : result.path("pages")) {
            int ordinal = page.path("ordinal").asInt();
            String text = page.path("text").asText("");
            Page match = pages.stream().filter(p -> p.ordinal() == ordinal).findFirst().orElseThrow();
            String vector = null;
            if (embedding != null && !text.isBlank()) {
                // e5 约定：passage 前缀（design.md D7）
                float[] vec = embedding.embed("passage: " + text);
                dimensions = vec.length;
                vector = vectorString(vec);
            }
            chunks.add(new RetrievalRepository.ChunkRow(match.id(), text, vector));
        }

        List<RetrievalRepository.ChunkRow> finalChunks = chunks;
        int finalDimensions = dimensions;
        tx.executeWithoutResult(status -> {
            retrievals.replaceChunks(generation.id(), revision.id(), finalChunks);
            if (embedding != null && finalDimensions > 0) {
                retrievals.setGenerationEmbedding(generation.id(), EMBEDDING_MODEL_NAME, finalDimensions);
            }
            retrievals.activateGeneration(revision.id(), generation.id());
        });
        ctx.progress().emit("index", "索引批次已激活（" + chunks.size() + " 页块）");
        log.info("job {} index generation {} activated: {} chunks, embedding={}",
                job.id(), generation.id(), chunks.size(), embedding != null);
        return JobResult.success("indexed");
    }

    private static String vectorString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }
}
