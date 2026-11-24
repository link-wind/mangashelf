# Proposal: benchmark-and-optimize-hybrid-search

## Why

当前平台的检索体系存在两个明显断层：

1. **站内数据未统一接入 Hybrid Search**：实测当前数据库已有 `external_catalog_items`（7512 部番剧，关联 `bangumi_subjects` 多语言原名/中文名/标签/简介）、`manga_catalog_items`（漫画目录）以及站内 `works` / `animations` 结构化文本，但现有 `search_chunks` 依赖耗时的漫画图片 OCR（库内为空），大量现成的站内与目录文本只能靠简单 `ILIKE` / `pg_trgm` 单路检索，无法按剧情描述、题材设定或跨语言别名召回。
2. **缺乏可量化、可复现的检索评测基线与重排优化**：`SearchService` 虽已有 `pg_trgm` + `pgvector` + RRF（`k=60`），但没有固定标注集、没有 `dev`/`test` 划分、没有 A/B/C/D 四组对照指标（`Recall@5/10`、`MRR@10`、`nDCG@10`、冷/暖 p50 & p95 时延、索引体积）以及错例分析，无法验证混合检索与重排相比基线的真实收益与成本。

根据项目决策：**不做 OCR 扩展，直接基于站内已有结构化数据（作品、动画、番剧目录+Bangumi 元数据、漫画目录）构建统一的 Hybrid Search、轻量 Rerank 与可复现评测体系**，并严守 `docs/DESIGN.md` 第 3.3 / 15 节的“授权过滤前置（不先召回全库再过滤）”和“显式搜索模式”边界。

## What Changes

1. **统一站内检索文档表（V28 迁移）**：新增 `search_documents` 表（覆盖 `WORK` / `ANIMATION` / `ANIME_CATALOG` / `MANGA_CATALOG`），维护主标题、别名/原名、聚合文本、`pg_trgm` GIN 索引、`vector(384)` HNSW 余弦索引、模型名标识与前置权限列（`owner_id` / `is_public`）；保持既有 `search_chunks` 兼容不破坏。
2. **零 OCR 索引同步服务**：新增 `SearchDocumentRepository` 与 `SearchDocumentSyncService`，支持将站内作品、动画、番剧目录（聚合 Bangumi 元数据）与漫画目录批量同步入 `search_documents` 并按配置生成 Embedding 向量。
3. **解耦四组检索策略与确定性轻量 Rerank**：
   - 新增 `HybridDocumentSearchService` 与 `LightweightReranker`，显式支持 4 种策略对照：
     - `A (LEXICAL)`：纯 `pg_trgm` 关键词/模糊召回
     - `B (VECTOR)`：纯 `pgvector` 余弦语义召回
     - `C (HYBRID_RRF)`：字面 + 向量两路 RRF 融合（`k` 与候选截断数经 `dev` 调优）
     - `D (HYBRID_RERANK)`：在 Hybrid Top-K 候选上叠加毫秒级确定性重排（融合标题精确/前缀匹配、别名命中、字符/bigram 覆盖率、字面相似度、向量余弦分与 RRF 分数）
   - 在 `SearchService` 与新服务中接入 Micrometer 检索指标（`mangashelf.search.duration`、`mangashelf.search.embed.duration`），线上 API 保持 `title`（默认不跑模型）与 `semantic`（使用 `HYBRID_RERANK`）的显式契约。
4. **120 条标注集 + 真实容器可复现 Benchmark 套件**：
   - 在 `server/src/test/resources/search-eval/` 建立版权清晰的站内语料集（覆盖作品、番剧、漫画、中英日别名、剧情设定、公开/私人权限样本）与 120 条人工标注查询（`dev` 40 条 / `test` 80 条，覆盖精确标题/别名、语义改写、拼写/口语噪声、跨类型定位 + 跨用户权限隔离断言）；
   - 新增基于真实 Testcontainers `pgvector/pgvector:pg18` 与真实本地 ONNX Embedding 模型的 `HybridSearchBenchmarkIT`，输出四组策略的冷/暖质量与时延对照、索引体积（`pg_relation_size`）及至少 10 个真实错例分析，写入 `docs/validation/benchmark-and-optimize-hybrid-search.md`。

## Capabilities

### New Capabilities

（归入既有 `content-retrieval` 规格演进。）

### Modified Capabilities

- `content-retrieval`：扩展支持基于站内结构化数据（无需 OCR）的统一文档混合检索、确定性轻量重排、检索可观测指标及权限前置隔离验证。

## Impact

- **数据库迁移**：新增 `V28__search_documents.sql`（仅新增表与索引，不改旧表）。
- **后端代码**：`server/src/main/java/com/mangashelf/retrieval/`（新增 `SearchDocumentRepository`、`SearchDocumentSyncService`、`LightweightReranker`、`HybridDocumentSearchService`、`SearchMetrics`；`SearchService` 与 `SearchController` 兼容扩展）。
- **测试与文档**：`server/src/test/resources/search-eval/`、`server/src/test/java/com/mangashelf/retrieval/HybridSearchBenchmarkIT.java`、`docs/validation/benchmark-and-optimize-hybrid-search.md`。
