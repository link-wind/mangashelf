# Design: benchmark-and-optimize-hybrid-search

> 对应 `docs/DESIGN.md` 第 3.3 / 15 / 20 / 21 节。本变更基于站内已有结构化文本（无需 OCR）扩展统一混合检索、轻量重排与量化评测，不引入通用搜索框架或外部搜索引擎服务（坚持 PostgreSQL `pg_trgm` + `pgvector`）。

## 1. 数据模型（`V28__search_documents.sql`）

新增单表 `search_documents`，以 `(doc_type, target_id)` 唯一标识站内可检索实体：

```sql
CREATE TABLE search_documents (
    id              bigserial PRIMARY KEY,
    doc_type        text NOT NULL CHECK (doc_type IN ('WORK', 'ANIMATION', 'ANIME_CATALOG', 'MANGA_CATALOG')),
    target_id       uuid NOT NULL,
    owner_id        uuid,
    is_public       boolean NOT NULL DEFAULT false,
    title           text NOT NULL,
    alt_titles      text NOT NULL DEFAULT '',
    body            text NOT NULL DEFAULT '',
    search_text     text NOT NULL,
    embedding       vector(384),
    embedding_model text,
    source_label    text NOT NULL DEFAULT '',
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (doc_type, target_id)
);
```

索引设计：
- `idx_search_docs_text_trgm`：`USING gin (search_text gin_trgm_ops)`（支撑字面模糊与中文短词召回）
- `idx_search_docs_title_trgm`：`USING gin (title gin_trgm_ops)`（支撑标题高精度召回）
- `idx_search_docs_embedding_hnsw`：`USING hnsw (embedding vector_cosine_ops)`（支撑 384 维余弦近似近邻召回）
- `idx_search_docs_visibility`：`(is_public, owner_id, doc_type)`（辅助权限前置过滤）

说明：
- `WORK` / `ANIMATION`：`is_public = (visibility = 'PUBLIC' AND status = 'PUBLISHED')`，`owner_id = 作品/动画所有者`；
- `ANIME_CATALOG` / `MANGA_CATALOG`：`is_public = true`，`owner_id = NULL`；
- `search_text` 由 `title + "\n" + alt_titles + "\n" + body` 拼接；索引更新采用 `ON CONFLICT (doc_type, target_id) DO UPDATE` 并在删除源实体时同步清理，避免幽灵记录。

## 2. 四组检索策略与确定性轻量 Reranker

在 `HybridDocumentSearchService` 定义显式策略枚举 `Strategy`：
- **`LEXICAL`（方案 A）**：SQL 前置权限过滤 + `pg_trgm`（`title` 与 `search_text` 相似度 + `ILIKE` 保底命中），按综合字面相关分排序；
- **`VECTOR`（方案 B）**：SQL 前置权限过滤 + `embedding_model = :model AND embedding IS NOT NULL` + `1 - (embedding <=> :queryVec::vector)` 余弦相似度排序；
- **`HYBRID_RRF`（方案 C）**：分别取 `LEXICAL` Top-N 与 `VECTOR` Top-N 候选（默认 `candidateLimit = max(limit * 4, 40)`），按 RRF 公式 $\sum \frac{1}{k + \text{rank}}$ 融合排序（`k` 在 `dev` 集上做网格对比后定参，默认基线 `k=60`）；
- **`HYBRID_RERANK`（方案 D）**：在 C 的融合候选池（Top-`rerankTopK`）上应用 `LightweightReranker`，纯 CPU 确定性打分（< 1ms，零外网调用、零 GPU 依赖），综合 5 个可解释特征：
  1. `rrfNorm`：归一化 RRF 融合分（保留双路共识排序）；
  2. `exactTitleBoost`：查询词与 `title`/`alt_titles` 精确或子串/前缀命中加权（保护精确标题与别名查询不被语义误飘）；
  3. `tokenCoverage`：归一化字符/词元（中文 2-gram + 英文词）在 `title + alt_titles + body` 中的覆盖率（提升含轻微 OCR/拼写噪声与口语改写时的排序稳定性）；
  4. `lexicalScore`：归一化字面相似度；
  5. `vectorScore`：归一化向量余弦相似度。

权重仅在 `dev` 集（40 条查询）上验证定型，`test` 集（80 条查询）严格盲测汇报。

## 3. Embedding 模型兼容性与真实环境说明

- 现有 `server/models/e5-small-v2.onnx`（384 维）可直接在本地离线加载，不强制联网下载新模型；
- 评测套件自动检测本机 `server/models/e5-small-v2.onnx`（或环境变量指定的 384 维 ONNX 模型）：
  - 使用 Spring AI `TransformersEmbeddingModel`（真实 ONNX 推理，非随机假向量）对语料和查询生成真实 384 维向量；
  - 若某类中文语义改写因 `e5-small-v2` 英文偏向导致方案 B 召回偏低，将如实记录在错例分析与限制章节中（严格遵守“不预设 Hybrid 一定全面胜出、如实汇报模型局限”的原则）。

## 4. 评测数据集与指标口径

- **语料规模**：`server/src/test/resources/search-eval/corpus.json`（版权清晰的自建动漫/漫画/站内作品样例库，涵盖中/英/日多语言标题别名、题材标签、剧情设定摘要，含公开与私人权限测试条目，并在基准测试中支持批量填充背景干扰条目测试索引体积与延迟）。
- **查询集**：`server/src/test/resources/search-eval/queries.json`（共 **120 条**标注查询：`dev` 40 条 + `test` 80 条），分 4 类：
  1. `exact_title_alias`（30 条：精确标题、英文/日文别名、简称）；
  2. `semantic_plot`（30 条：剧情设定、情绪风格、英文/中英混合语义改写）；
  3. `typo_noisy`（30 条：拼写错误、缺字、口语化描述）；
  4. `cross_type_topic`（30 条：跨番剧/漫画/站内作品的题材与主题定位）。
- **分级相关性**：`2 = exact`、`1 = relevant`、`0 = irrelevant`。
- **指标公式**：
  - `Recall@K`：前 K 结果命中的相关文档（grade ≥ 1）占该查询总相关文档数的比例；
  - `MRR@10`：前 10 结果中首个相关文档（grade ≥ 1）倒数排名的均值；
  - `nDCG@10`：使用分级增益 $\frac{2^{\text{rel}} - 1}{\log_2(i + 2)}$ 计算的归一化折损累计增益；
  - 时延：区分冷启动第 1 轮与暖缓存（预热后重放）的 p50 / p95（ms）；
  - 索引体积：通过 `pg_relation_size` 实测 `search_documents` 主表、GIN trigram 索引与 HNSW 向量索引字节数。

## 5. Micrometer 指标

新增 `SearchMetrics`（注册到现有 `MeterRegistry`）：
- `mangashelf.search.duration`（Timer，tag: `mode`, `strategy`）
- `mangashelf.search.embed.duration`（Timer，tag: `phase=query|index`）
- `mangashelf.search.requests.total`（Counter，tag: `mode`, `strategy`）
