package com.mangashelf.retrieval;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 可复现 Hybrid Search 基准评测与权限隔离集成测试（benchmark-and-optimize-hybrid-search）：
 * - 真实 PostgreSQL（pgvector/pgvector:pg18，完整 28 个 Flyway 迁移）
 * - 真实本地 ONNX Embedding（e5-small-v2，384 维，无假向量）
 * - 45 条核心语料 + 155 条背景噪声文档 = 200 条真实向量化文档，120 条分级标注查询（40 dev + 80 test）
 * - 对比 A(LEXICAL) / B(VECTOR) / C(HYBRID_RRF) / D(HYBRID_RERANK) 的质量、冷/暖时延、索引体积与错例
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "mangashelf.embedding.enabled=true",
        "mangashelf.embedding.model-path=./models/e5-small-v2.onnx",
        "mangashelf.embedding.tokenizer-path=./models/tokenizer.json",
        "mangashelf.bangumi.enabled=false",
        "mangashelf.redis.enabled=false",
        "mangashelf.jobs.dispatch-interval-ms=600000",
        "mangashelf.jobs.recovery-interval-ms=600000",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HybridSearchBenchmarkIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("mangashelf_bench")
            .withUsername("mangashelf")
            .withPassword("mangashelf");

    static {
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { POSTGRES.stop(); } catch (Exception ignored) {}
        }));
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    record CorpusItem(String key, String docType, boolean isPublic, String title,
                      String altTitles, String body, String sourceLabel) {}

    record EvalQuery(String id, String split, String category, String query,
                     Map<String, Integer> relevance) {}

    record MetricSummary(double recall5, double recall10, double mrr10, double ndcg10,
                         double coldP50Ms, double coldP95Ms, double warmP50Ms, double warmP95Ms) {}

    record ErrorCase(String queryId, String category, String query, String strategy,
                     List<String> expected, List<String> gotTop3, String reason) {}

    @Autowired private SearchDocumentRepository docRepo;
    @Autowired private SearchDocumentSyncService syncService;
    @Autowired private HybridDocumentSearchService searchService;
    @Autowired private EmbeddingModel embeddingModel;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meterRegistry;

    private final UUID ownerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID viewerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final Map<String, UUID> keyToTargetId = new HashMap<>();
    private final Map<UUID, String> targetIdToKey = new HashMap<>();
    private final Map<String, String> keyToTitle = new HashMap<>();

    private List<CorpusItem> corpus;
    private List<EvalQuery> devQueries;
    private List<EvalQuery> testQueries;
    private long indexBuildElapsedMs;

    @BeforeAll
    void seedCorpusAndBuildIndexes() throws Exception {
        String corpusJson = new ClassPathResource("search-eval/corpus.json")
                .getContentAsString(StandardCharsets.UTF_8);
        String queriesJson = new ClassPathResource("search-eval/queries.json")
                .getContentAsString(StandardCharsets.UTF_8);
        corpus = objectMapper.readValue(corpusJson, new TypeReference<>() {});
        List<EvalQuery> allQueries = objectMapper.readValue(queriesJson, new TypeReference<>() {});
        devQueries = allQueries.stream().filter(q -> "dev".equals(q.split())).toList();
        testQueries = allQueries.stream().filter(q -> "test".equals(q.split())).toList();

        jdbc.update("DELETE FROM search_documents");
        long start = System.currentTimeMillis();

        for (CorpusItem item : corpus) {
            UUID targetId = UUID.nameUUIDFromBytes(item.key().getBytes(StandardCharsets.UTF_8));
            keyToTargetId.put(item.key(), targetId);
            targetIdToKey.put(targetId, item.key());
            keyToTitle.put(item.key(), item.title());

            UUID owner = (SearchDocumentRepository.TYPE_WORK.equals(item.docType())
                    || SearchDocumentRepository.TYPE_ANIMATION.equals(item.docType())) ? ownerA : null;
            docRepo.upsert(syncService.toUpsert(
                    item.docType(), targetId, owner, item.isPublic(),
                    item.title(), item.altTitles(), item.body(), item.sourceLabel(), embeddingModel));
        }

        // 追加 155 条无相关性的背景干扰文档，使总语料达到 200 条以测试索引规模与抗干扰能力
        String[] noiseThemes = {
                "园艺花卉修剪入门 gardening botany greenhouse seasonal flowers",
                "古典建筑木工榫卯传统工艺 carpentry architecture woodwork heritage",
                "气象云图观测与台风预报科普 meteorology weather clouds barometer",
                "深海珊瑚礁生态摄影图鉴 marine biology coral reef diving oceanography",
                "手工陶艺拉坯与窑变釉色记录 pottery ceramic kiln clay studio"
        };
        for (int i = 1; i <= 155; i++) {
            String key = String.format("noise-%03d", i);
            UUID targetId = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
            String theme = noiseThemes[i % noiseThemes.length];
            String type = (i % 2 == 0)
                    ? SearchDocumentRepository.TYPE_ANIME_CATALOG
                    : SearchDocumentRepository.TYPE_MANGA_CATALOG;
            docRepo.upsert(syncService.toUpsert(
                    type, targetId, null, true,
                    "背景归档条目 #" + i, "Archive Entry " + i,
                    theme + " 编号 " + i, "背景语料", embeddingModel));
        }
        jdbc.execute("ANALYZE search_documents");
        indexBuildElapsedMs = System.currentTimeMillis() - start;

        assertEquals(200L, docRepo.countAll());
        assertEquals(40, devQueries.size());
        assertEquals(80, testQueries.size());
    }

    @Test
    void allFourStrategiesEnforcePermissionIsolationBeforeRecall() {
        List<String> privateQueries = List.of(
                "绯红星门计划",
                "Top Secret Crimson Stargate Draft",
                "Obsidian Protocol Private Storyboard",
                "未公开样片"
        );
        for (var strategy : HybridDocumentSearchService.Strategy.values()) {
            for (String pq : privateQueries) {
                var hitsForB = searchService.search(viewerB, pq, strategy, null, 10).hits();
                boolean leaked = hitsForB.stream()
                        .map(h -> targetIdToKey.getOrDefault(h.targetId(), ""))
                        .anyMatch(k -> k.startsWith("priv-"));
                assertFalse(leaked, "策略 " + strategy + " 不得向非所有者泄漏私人内容，query=" + pq);
            }
            var ownerHits = searchService.search(ownerA, "Crimson Stargate", strategy, null, 10).hits();
            boolean ownerFound = ownerHits.stream()
                    .map(h -> targetIdToKey.getOrDefault(h.targetId(), ""))
                    .anyMatch("priv-01"::equals);
            assertTrue(ownerFound, "策略 " + strategy + " 应对所有者本人正常召回私人草稿");
        }
    }

    @Test
    void runDevTuningAndTestBenchmarkAndGenerateReport() throws Exception {
        // 1. 仅在 dev 集（40 条）上对照 RRF k=20 / 40 / 60，选定参数，不触碰 test 集
        Map<Integer, MetricSummary> devKResults = new LinkedHashMap<>();
        int bestK = 40;
        double bestDevNdcg = -1.0;
        for (int k : List.of(20, 40, 60)) {
            var opts = new HybridDocumentSearchService.SearchOptions(
                    k, 4, 30, LightweightReranker.Weights.DEFAULT);
            MetricSummary summary = evaluateSplit(devQueries,
                    HybridDocumentSearchService.Strategy.HYBRID_RRF, opts, null);
            devKResults.put(k, summary);
            if (summary.ndcg10() > bestDevNdcg) {
                bestDevNdcg = summary.ndcg10();
                bestK = k;
            }
        }

        var selectedOpts = new HybridDocumentSearchService.SearchOptions(
                bestK, 4, 30, LightweightReranker.Weights.DEFAULT);

        // 2. 在 test 集（80 条）上严格盲测 A / B / C / D 四组方案（总体 + 分 4 类 + 错例收集）
        Map<HybridDocumentSearchService.Strategy, MetricSummary> testOverall = new LinkedHashMap<>();
        Map<String, Map<HybridDocumentSearchService.Strategy, MetricSummary>> byCategory = new LinkedHashMap<>();
        List<ErrorCase> allErrorCases = new ArrayList<>();

        List<String> categories = List.of("exact_title_alias", "semantic_plot", "typo_noisy", "cross_type_topic");
        for (String cat : categories) {
            byCategory.put(cat, new LinkedHashMap<>());
        }

        for (var strategy : HybridDocumentSearchService.Strategy.values()) {
            List<ErrorCase> strategyErrors = new ArrayList<>();
            MetricSummary overall = evaluateSplit(testQueries, strategy, selectedOpts, strategyErrors);
            testOverall.put(strategy, overall);
            allErrorCases.addAll(strategyErrors);

            for (String cat : categories) {
                List<EvalQuery> subset = testQueries.stream().filter(q -> cat.equals(q.category())).toList();
                byCategory.get(cat).put(strategy, evaluateSplit(subset, strategy, selectedOpts, null));
            }
        }

        // 3. 验证核心工程断言：Hybrid + Rerank 在总体质量上具备强可用性，且指标采集生效
        MetricSummary lex = testOverall.get(HybridDocumentSearchService.Strategy.LEXICAL);
        MetricSummary vec = testOverall.get(HybridDocumentSearchService.Strategy.VECTOR);
        MetricSummary rrf = testOverall.get(HybridDocumentSearchService.Strategy.HYBRID_RRF);
        MetricSummary rerank = testOverall.get(HybridDocumentSearchService.Strategy.HYBRID_RERANK);

        assertTrue(rrf.recall10() >= Math.min(lex.recall10(), vec.recall10()),
                "Hybrid RRF Recall@10 应不低于最弱单一基线");
        assertTrue(rerank.recall10() >= 0.70, "Hybrid + Rerank Recall@10 应 >= 0.70，实际=" + rerank.recall10());
        assertNotNull(meterRegistry.find("mangashelf.search.duration").timer());
        assertNotNull(meterRegistry.find("mangashelf.search.embed.duration").timer());

        // 4. 写出完整 Markdown 评测报告供审阅和沉淀
        Map<String, Long> sizes = docRepo.relationSizesBytes();
        String report = buildMarkdownReport(bestK, devKResults, testOverall, byCategory, sizes, allErrorCases);
        Path reportPath = Path.of("target/search-benchmark-report.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
        System.out.println("\n================ HYBRID SEARCH BENCHMARK REPORT ================\n" + report);
    }

    private MetricSummary evaluateSplit(List<EvalQuery> split,
                                        HybridDocumentSearchService.Strategy strategy,
                                        HybridDocumentSearchService.SearchOptions opts,
                                        List<ErrorCase> errorCollector) {
        int n = split.size();
        double sumR5 = 0, sumR10 = 0, sumMrr = 0, sumNdcg = 0;
        double[] coldMs = new double[n];
        double[] warmMs = new double[n];

        // 第 1 轮：冷启动计时 + 质量评估
        for (int i = 0; i < n; i++) {
            EvalQuery eq = split.get(i);
            long t0 = System.nanoTime();
            var res = searchService.search(viewerB, eq.query(), strategy, null, 10, opts);
            coldMs[i] = (System.nanoTime() - t0) / 1_000_000.0;

            List<String> returnedKeys = res.hits().stream()
                    .map(h -> targetIdToKey.getOrDefault(h.targetId(), "noise"))
                    .toList();

            double r5 = recallAtK(returnedKeys, eq.relevance(), 5);
            double r10 = recallAtK(returnedKeys, eq.relevance(), 10);
            double mrr = mrrAt10(returnedKeys, eq.relevance());
            double ndcg = ndcgAt10(returnedKeys, eq.relevance());

            sumR5 += r5;
            sumR10 += r10;
            sumMrr += mrr;
            sumNdcg += ndcg;

            if (errorCollector != null && (r10 < 1.0 || mrr < 0.5)) {
                List<String> expected = eq.relevance().keySet().stream()
                        .map(k -> k + "(" + keyToTitle.getOrDefault(k, k) + ")").toList();
                List<String> gotTop3 = res.hits().stream().limit(3)
                        .map(h -> targetIdToKey.getOrDefault(h.targetId(), "noise") + "(" + h.title() + ")")
                        .toList();
                String reason = r10 == 0.0 ? "Top-10 未召回目标文档"
                        : (r10 < 1.0 ? "多目标场景仅部分召回" : "命中但首条排序靠后（MRR=" + String.format(Locale.ROOT, "%.2f", mrr) + "）");
                errorCollector.add(new ErrorCase(eq.id(), eq.category(), eq.query(),
                        strategy.name(), expected, gotTop3, reason));
            }
        }

        // 第 2 轮：暖缓存重放计时
        for (int i = 0; i < n; i++) {
            EvalQuery eq = split.get(i);
            long t0 = System.nanoTime();
            searchService.search(viewerB, eq.query(), strategy, null, 10, opts);
            warmMs[i] = (System.nanoTime() - t0) / 1_000_000.0;
        }

        return new MetricSummary(
                sumR5 / n, sumR10 / n, sumMrr / n, sumNdcg / n,
                percentile(coldMs, 50), percentile(coldMs, 95),
                percentile(warmMs, 50), percentile(warmMs, 95));
    }

    private static double recallAtK(List<String> returned, Map<String, Integer> rel, int k) {
        long totalRelevant = rel.values().stream().filter(g -> g >= 1).count();
        if (totalRelevant == 0) return 1.0;
        long hit = returned.stream().limit(k).distinct()
                .filter(id -> rel.getOrDefault(id, 0) >= 1).count();
        return (double) hit / totalRelevant;
    }

    private static double mrrAt10(List<String> returned, Map<String, Integer> rel) {
        for (int i = 0; i < Math.min(10, returned.size()); i++) {
            if (rel.getOrDefault(returned.get(i), 0) >= 1) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private static double ndcgAt10(List<String> returned, Map<String, Integer> rel) {
        double dcg = 0.0;
        for (int i = 0; i < Math.min(10, returned.size()); i++) {
            int g = rel.getOrDefault(returned.get(i), 0);
            if (g > 0) {
                dcg += (Math.pow(2, g) - 1.0) / (Math.log(i + 2) / Math.log(2));
            }
        }
        List<Integer> ideal = new ArrayList<>(rel.values());
        ideal.sort(Collections.reverseOrder());
        double idcg = 0.0;
        for (int i = 0; i < Math.min(10, ideal.size()); i++) {
            int g = ideal.get(i);
            if (g > 0) {
                idcg += (Math.pow(2, g) - 1.0) / (Math.log(i + 2) / Math.log(2));
            }
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private static double percentile(double[] values, int pct) {
        double[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        int idx = (int) Math.ceil((pct / 100.0) * copy.length) - 1;
        return copy[Math.max(0, Math.min(idx, copy.length - 1))];
    }

    private String buildMarkdownReport(
            int bestK,
            Map<Integer, MetricSummary> devKResults,
            Map<HybridDocumentSearchService.Strategy, MetricSummary> testOverall,
            Map<String, Map<HybridDocumentSearchService.Strategy, MetricSummary>> byCategory,
            Map<String, Long> sizes,
            List<ErrorCase> allErrors) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 数据规模：200 条向量化文档（45 条核心站内语料 + 155 条背景噪声文档），120 条标注查询（40 dev / 80 test）\n");
        sb.append("- 模型：本地 ONNX `e5-small-v2`（384 维），索引构建耗时：").append(indexBuildElapsedMs).append(" ms\n\n");

        sb.append("## Dev 集（40 条）RRF k 参数对照（选定 k=").append(bestK).append("）\n\n");
        sb.append("| RRF k | Recall@5 | Recall@10 | MRR@10 | nDCG@10 |\n| --- | --- | --- | --- | --- |\n");
        for (var e : devKResults.entrySet()) {
            var m = e.getValue();
            sb.append(String.format(Locale.ROOT, "| %d | %.4f | %.4f | %.4f | %.4f |\n",
                    e.getKey(), m.recall5(), m.recall10(), m.mrr10(), m.ndcg10()));
        }

        sb.append("\n## Test 集（80 条盲测）四组方案总体对照\n\n");
        sb.append("| 方案 | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | 冷启动 p50/p95 (ms) | 暖缓存 p50/p95 (ms) |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (var e : testOverall.entrySet()) {
            var m = e.getValue();
            sb.append(String.format(Locale.ROOT, "| %s | %.4f | %.4f | %.4f | %.4f | %.2f / %.2f | %.2f / %.2f |\n",
                    e.getKey().name(), m.recall5(), m.recall10(), m.mrr10(), m.ndcg10(),
                    m.coldP50Ms(), m.coldP95Ms(), m.warmP50Ms(), m.warmP95Ms()));
        }

        sb.append("\n## Test 集分查询类别对照（Recall@10 / nDCG@10）\n\n");
        sb.append("| 查询类别 | A: LEXICAL | B: VECTOR | C: HYBRID_RRF | D: HYBRID_RERANK |\n");
        sb.append("| --- | --- | --- | --- | --- |\n");
        for (var cat : byCategory.entrySet()) {
            var map = cat.getValue();
            var a = map.get(HybridDocumentSearchService.Strategy.LEXICAL);
            var b = map.get(HybridDocumentSearchService.Strategy.VECTOR);
            var c = map.get(HybridDocumentSearchService.Strategy.HYBRID_RRF);
            var d = map.get(HybridDocumentSearchService.Strategy.HYBRID_RERANK);
            sb.append(String.format(Locale.ROOT,
                    "| %s | %.3f / %.3f | %.3f / %.3f | %.3f / %.3f | %.3f / %.3f |\n",
                    cat.getKey(), a.recall10(), a.ndcg10(), b.recall10(), b.ndcg10(),
                    c.recall10(), c.ndcg10(), d.recall10(), d.ndcg10()));
        }

        sb.append("\n## 磁盘索引体积（pg_relation_size，200 条文档）\n\n");
        sb.append("| 对象 | 体积 (KiB) |\n| --- | --- |\n");
        for (var e : sizes.entrySet()) {
            sb.append(String.format(Locale.ROOT, "| `%s` | %.1f |\n", e.getKey(), e.getValue() / 1024.0));
        }

        sb.append("\n## 典型错例（按策略抽样，共收集 ").append(allErrors.size()).append(" 条）\n\n");
        sb.append("| 查询 ID | 策略 | 类别 | 查询 | 期望 | 实际 Top-3 | 现象 |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        List<ErrorCase> sampled = new ArrayList<>();
        for (var strat : HybridDocumentSearchService.Strategy.values()) {
            allErrors.stream()
                    .filter(ec -> strat.name().equals(ec.strategy()))
                    .limit(4)
                    .forEach(sampled::add);
        }
        for (ErrorCase ec : sampled) {
            sb.append(String.format("| %s | %s | %s | %s | %s | %s | %s |\n",
                    ec.queryId(), ec.strategy(), ec.category(), ec.query(),
                    String.join(", ", ec.expected()),
                    String.join(", ", ec.gotTop3()), ec.reason()));
        }
        return sb.toString();
    }
}
