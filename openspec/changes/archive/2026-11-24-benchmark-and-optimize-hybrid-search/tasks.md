# Tasks: benchmark-and-optimize-hybrid-search

## 1. 统一站内检索表与索引同步（零 OCR）

- [x] 1.1 新增 Flyway 迁移 `V28__search_documents.sql`（建表 `search_documents`、trgm GIN 索引、384 维 HNSW 索引与可见性索引）
- [x] 1.2 实现 `SearchDocumentRepository`（幂等 upsert、删除清理、前置权限过滤的 `pg_trgm` 字面召回与同模型 `pgvector` 余弦召回、索引体积统计查询）
- [x] 1.3 实现 `SearchDocumentSyncService`（将站内 `works`、`animations`、`external_catalog_items` + `bangumi_subjects`、`manga_catalog_items` 聚合同步入 `search_documents` 并按配置生成 Embedding）

## 2. 四组检索策略、轻量 Rerank 与可观测指标

- [x] 2.1 实现 `SearchMetrics`（Micrometer 记录 `mangashelf.search.duration`、`mangashelf.search.embed.duration`、`mangashelf.search.requests.total`）
- [x] 2.2 实现 `LightweightReranker`（基于 RRF、标题/别名精确与前缀命中、字符/bigram 覆盖率、字面相似度与向量余弦分的确定性毫秒级重排）
- [x] 2.3 实现 `HybridDocumentSearchService`（支持 `LEXICAL` / `VECTOR` / `HYBRID_RRF` / `HYBRID_RERANK` 四种策略与可调 RRF 参数，并在 `SearchService` 接入检索指标与统一文档检索能力）

## 3. 固定标注集与可复现 Benchmark 套件

- [x] 3.1 创建 `server/src/test/resources/search-eval/corpus.json`（覆盖作品、动画、番剧与漫画目录、中英日别名、题材与剧情简介、公开/私人权限样本）
- [x] 3.2 创建 `server/src/test/resources/search-eval/queries.json`（120 条分级标注查询：`dev` 40 条 + `test` 80 条，覆盖精确标题/别名、语义剧情改写、拼写/口语噪声、跨类型主题 4 类）
- [x] 3.3 实现 `HybridSearchBenchmarkIT`（基于真实 Testcontainers `pgvector/pgvector:pg18` 与真实本地 ONNX Embedding 模型运行，在 `dev` 验证参数、在 `test` 盲测输出 A/B/C/D 四组 Recall@5/10、MRR@10、nDCG@10、冷/暖 p50 & p95 时延、索引体积、权限隔离断言与 ≥10 个真实错例分析）

## 4. 实测验证与文档沉淀

- [x] 4.1 运行 `cd server && ./mvnw test` 确认全部单测、故障注入集成测试与 Hybrid Search 评测测试通过
- [x] 4.2 将四组对照实验实测数据、硬件/数据规模说明与 ≥10 个真实错例分析写入 `docs/validation/benchmark-and-optimize-hybrid-search.md`
- [x] 4.3 基于两项变更的真实实测数据更新 `README.md` 中的架构权衡、测试运行命令与实测摘要
