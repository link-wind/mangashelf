# Delta for content-retrieval

## ADDED Requirements

### Requirement: 站内结构化数据统一混合检索（零 OCR）

系统 MUST 支持在不依赖图片 OCR 的前提下，将站内作品、站内动画、番剧目录（聚合 Bangumi 标题/原名/标签/简介）与漫画目录同步至统一检索文档表 `search_documents`，并在单次检索中同时支持字面（`pg_trgm`）、语义向量（`pgvector`）、RRF 混合召回与轻量重排（Rerank）。所有召回分支 MUST 在 SQL `WHERE` 子句中前置过滤访问权限（`is_public = true OR owner_id = :viewerId`），私人草稿 MUST NOT 在任何策略下向非所有者泄漏。

#### Scenario: 四组检索策略均严格隔离私人内容

- **WHEN** 用户 A 拥有一条 `is_public = false` 的私人站内作品进入 `search_documents`，用户 B 分别以 `LEXICAL`、`VECTOR`、`HYBRID_RRF`、`HYBRID_RERANK` 四种策略搜索该作品标题与简介
- **THEN** 四种策略对用户 B 返回的结果均不包含该私人作品，对用户 A 则可正常召回

#### Scenario: 同模型向量一致性过滤

- **WHEN** 语义或混合检索使用模型 M 查询 `search_documents`
- **THEN** 向量分支仅召回 `embedding_model = M` 且 `embedding IS NOT NULL` 的文档，不混用不同模型产出的向量

### Requirement: 可复现混合检索基准评测与观测指标

系统 MUST 提供基于固定标注数据集（区分 `dev` 与 `test` 划分）的可复现离线评测套件，在真实 PostgreSQL（`pgvector` + `pg_trgm`）环境下测量 `LEXICAL`、`VECTOR`、`HYBRID_RRF`、`HYBRID_RERANK` 四组方案的 `Recall@5`、`Recall@10`、`MRR@10`、`nDCG@10`、冷启动与暖缓存查询 p50/p95 时延及索引体积，并输出错例分析；同时 MUST 通过 Micrometer 暴露搜索总耗时与 Embedding 耗时指标。

#### Scenario: 真实容器跑通四组对照评测

- **WHEN** 运行 `HybridSearchBenchmarkIT`
- **THEN** 四组策略在 `test` 集上完成冷/暖两轮评测，产出完整质量/延迟/索引体积报告与错例清单，且权限隔离断言全部通过
