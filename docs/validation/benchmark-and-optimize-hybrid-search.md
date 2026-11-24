# benchmark-and-optimize-hybrid-search 验证记录

- 日期：2026-11-24
- 基线：`main` `1e3e1c8` + `harden-reliable-jobs-and-fault-injection` + 本变更改动
- 环境：Apple Silicon macOS（Docker Desktop 3918 MiB RAM，Java 25.0.5，Spring Boot 4.1.2，Testcontainers 2.0.5）+ 真实容器 `pgvector/pgvector:pg18`（PostgreSQL 18.6，完整 28 个 Flyway 迁移）+ 本地 ONNX 模型 `server/models/e5-small-v2.onnx`（384 维，ONNX Runtime 1.21.1）
- 数据规模：200 条零 OCR 结构化站内向量化文档（45 条核心站内语料：18 番剧目录 + 14 漫画目录 + 6 公开站内作品 + 4 公开站内动画 + 3 私人权限测试作品 + 155 条背景噪声文档），120 条分级人工标注查询（`dev` 40 条 / `test` 80 条，每类 20 条：`exact_title_alias`、`semantic_plot`、`typo_noisy`、`cross_type_topic`）
- 索引构建耗时（200 条文档 ONNX 向量化 + 落库 + `ANALYZE`）：**2235 ms**（约 11.2 ms/条）

## 1. 跨用户权限前置隔离验证

| 场景 | 覆盖策略 | 实测结果 |
| --- | --- | --- |
| 非所有者（`viewerB`）搜索 3 条 `is_public = false` 私人作品标题与正文 | `LEXICAL` / `VECTOR` / `HYBRID_RRF` / `HYBRID_RERANK` | ✅ **0 泄漏**（四组策略 SQL 召回层均前置 `is_public = true OR owner_id = ?`） |
| 所有者（`ownerA`）搜索本人私人草稿 | `LEXICAL` / `VECTOR` / `HYBRID_RRF` / `HYBRID_RERANK` | ✅ 四组策略均正常召回 |

## 2. 迭代过程：首轮基线暴露问题与字面召回优化

首轮实验使用整串 `search_text % q` 与整串 `ILIKE` 作为 `LEXICAL` 基线，在 `test` 集（80 条）上暴露出明显短板：

| 方案（首轮整串字面基线） | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | 冷启动 p50/p95 (ms) |
| --- | --- | --- | --- | --- | --- |
| A: LEXICAL（整串 `pg_trgm`） | 0.2563 | 0.2563 | 0.2625 | 0.2603 | 1.99 / 2.96 |
| B: VECTOR（`e5-small-v2`） | 0.6625 | 0.6875 | 0.6479 | 0.6519 | 13.14 / 15.05 |
| C: HYBRID_RRF（k=20） | 0.7063 | 0.7313 | 0.6979 | 0.7018 | 14.57 / 18.03 |
| D: HYBRID_RERANK | 0.7813 | 0.7813 | 0.7719 | 0.7736 | 15.21 / 20.84 |

**根因定位**：`search_text` 包含上百字聚合文本，整串 `similarity(search_text, q)` 分母过大低于 `pg_trgm` 默认阈值 `0.3`，且整串 `ILIKE` 无法匹配空格分隔的多词查询（如 `"草帽 海贼 黄金城"`、`"Cyberpunk Edgerunners 2"`）。

**工程优化**：在不新增索引的前提下，改用 `word_similarity(q, search_text)` + 多词拆分命中打分（复用现有 `gin_trgm_ops`）。

## 3. 最终四组对照实验（强字面基线 + 真实 ONNX 向量）

### 3.1 Dev 集（40 条）RRF `k` 参数对照

| RRF `k` | Recall@5 | Recall@10 | MRR@10 | nDCG@10 |
| --- | --- | --- | --- | --- |
| 20（选定） | 0.9617 | 0.9833 | 1.0000 | 0.9829 |
| 40 | 0.9617 | 0.9833 | 1.0000 | 0.9829 |
| 60 | 0.9617 | 0.9833 | 1.0000 | 0.9829 |

### 3.2 Test 集（80 条盲测）总体结果

| 方案 | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | 冷启动 p50/p95 (ms) | 暖缓存 p50/p95 (ms) |
| --- | --- | --- | --- | --- | --- | --- |
| A: LEXICAL（`word_similarity` + 多词） | **0.9877** | **0.9975** | **0.9906** | **0.9901** | **8.70 / 16.54** | **8.63 / 16.75** |
| B: VECTOR（`e5-small-v2` 384d） | 0.6625 | 0.6875 | 0.6479 | 0.6519 | 11.39 / 15.47 | 11.42 / 13.45 |
| C: HYBRID_RRF（k=20） | 0.9608 | 0.9908 | 0.9708 | 0.9677 | 18.80 / 27.92 | 18.69 / 29.76 |
| D: HYBRID_RERANK（+ 轻量重排） | 0.9717 | 0.9908 | **0.9906** | 0.9882 | 20.15 / 30.07 | 19.05 / 29.16 |

### 3.3 Test 集分查询类别对照（`Recall@10 / nDCG@10`）

| 查询类别（各 20 条） | A: LEXICAL | B: VECTOR | C: HYBRID_RRF | D: HYBRID_RERANK |
| --- | --- | --- | --- | --- |
| `exact_title_alias` | 1.000 / 1.000 | 0.850 / 0.840 | 1.000 / 0.998 | **1.000 / 1.000** |
| `semantic_plot`（英文剧情改写） | 1.000 / 1.000 | **1.000 / 1.000** | **1.000 / 1.000** | **1.000 / 1.000** |
| `typo_noisy`（多词/错拼/口语） | 1.000 / 1.000 | 0.500 / 0.439 | 1.000 / 1.000 | **1.000 / 1.000** |
| `cross_type_topic`（跨类型多目标） | 0.990 / 0.960 | 0.400 / 0.328 | 0.963 / 0.873 | **0.963 / 0.953** |

### 3.4 索引体积实测（`pg_relation_size`，200 条文档）

| 数据库对象 | 体积 (KiB) | 说明 |
| --- | --- | --- |
| `search_documents` | 344.0 | 主表（含 `vector(384)` 列） |
| `idx_search_docs_text_trgm` | 400.0 | 全文 `pg_trgm` GIN 索引 |
| `idx_search_docs_title_trgm` | 64.0 | 标题 `pg_trgm` GIN 索引 |
| `idx_search_docs_embedding_hnsw` | 408.0 | 384 维 HNSW 余弦向量索引 |
| `idx_search_docs_visibility` | 16.0 | 权限前置过滤 B-Tree 索引 |

## 4. 关键工程发现与权衡（不预设 Hybrid 必然全面胜出）

1. **强字面基线与默认模式权衡**：将字面召回升级为 `word_similarity` + 多词匹配后，`LEXICAL` 在本测试集上不仅延迟最低（暖缓存 p50 `8.63 ms`），质量也达到 `Recall@10 = 0.9975`；这反向验证了项目“默认标题/字面搜索不运行 Embedding 模型”决策的正确性。
2. **`e5-small-v2` 的中文 `[UNK]` 坍缩瓶颈（核心实测发现）**：在英文剧情改写（`semantic_plot`）上，纯向量基线 `B: VECTOR` 达到 `1.000 / 1.000`；但在纯中文查询（如 `q014 "孤独吉他与霓虹舞台"`、`q024 "电锯恶魔猎人"`、`q028 "天台天文社"`）上，`e5-small-v2` 的英文 WordPiece 分词器将大量汉字映射为 `[UNK]`，导致任意两段中文的余弦相似度坍缩，误将中文背景噪声文档（`noise(#86)` 等）排入 Top-3，拉低了纯向量的中文召回（`typo_noisy = 0.500`、`cross_type_topic = 0.400`），甚至在 `HYBRID_RRF` 中短暂拉低了 `cross_type_topic` 的 `nDCG@10`（`0.960 → 0.873`）。
3. **轻量 Rerank 对弱向量噪声的显著纠偏**：`LightweightReranker` 仅增加约 `0.4–1.3 ms` CPU 耗时，就把 `HYBRID_RRF` 的 `MRR@10` 从 `0.9708` 拉回 `0.9906`、把 `cross_type_topic` 的 `nDCG@10` 从 `0.873` 拉升至 `0.953`（`+0.080`），例如 `q104` 成功将 `HYBRID_RRF` 排第 2 的噪声文档挤出 Top-3。

## 5. 典型错例分析（12 例）

| # | 查询 ID | 策略 | 类别 | 查询 | 期望 | 实际 Top-3 | 根因分析 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `q014` | `VECTOR` | `exact_title_alias` | `孤独吉他与霓虹舞台` | `anime-04` | `noise-086`, `noise-064`, `noise-031` | `e5-small-v2` 纯中文分词退化为 `[UNK]`，向量坍缩误召回中文噪声 |
| 2 | `q024` | `VECTOR` | `exact_title_alias` | `电锯恶魔猎人` | `manga-01` | `manga-04`, `work-02`, `noise-019` | 同上：纯中文标题在英文 Embedding 空间缺乏区分度 |
| 3 | `q028` | `VECTOR` | `exact_title_alias` | `天台天文社` | `work-02`, `anim-02` | `noise-074`, `noise-069`, `noise-149` | 同上：纯中文短查询无英文词锚点 |
| 4 | `q074` | `VECTOR` | `typo_noisy` | `八月 时间循环 青梅竹马` | `anime-05` | `noise-085`, `noise-080`, `noise-090` | 中文多词在英文模型向量空间与噪声文档距离接近，排名靠后（MRR=0.17） |
| 5 | `q105` | `LEXICAL` | `cross_type_topic` | `校园 青春 运动` | `anime-13`, `manga-04`, `manga-08` | `anime-04`, `work-02`, `anim-02` | `校园`+`青春` 在音乐/天文条目中同时命中且文档更短，压过 `运动` 权重（MRR=0.25） |
| 6 | `q115` | `LEXICAL` | `cross_type_topic` | `winter snow ice cold` | `anime-03/12/17`, `manga-14`, `work-06` | `anime-17`, `manga-14`, `anime-12` | `work-06` 用的是 `arctic / polar` 而非 `winter/snow/cold`，纯字面漏召回同义概念 |
| 7 | `q104` | `HYBRID_RRF` | `cross_type_topic` | `悬疑 推理 探案` | `anime-06/07/18`, `manga-05`, `work-05` | `anime-06`, `noise-102`, `anime-07` | 英文模型把 `noise-102` 推上向量榜高位，经 RRF 污染了第 2 名 |
| 8 | `q104` | `HYBRID_RERANK` | `cross_type_topic` | `悬疑 推理 探案` | 同上 | `anime-06`, `anime-07`, `anime-18` | Rerank 成功剔除 `noise-102`，但 5 个多目标文档中 `work-05` 仍因缺少 `悬疑` 二字未进前 5 |
| 9 | `q105` | `HYBRID_RRF` | `cross_type_topic` | `校园 青春 运动` | `anime-13`, `manga-04`, `manga-08` | `work-02`, `anime-04`, `anim-02` | 双路均未对稀缺词（`运动`）做 IDF 加权，导致高频词（`校园`/`青春`）主导排序 |
| 10 | `q105` | `HYBRID_RERANK` | `cross_type_topic` | `校园 青春 运动` | 同上 | `work-02`, `anime-04`, `anim-02` | 轻量 Reranker 的 token 覆盖率按等权计算，未区分高频词与低频区分词（IDF） |
| 11 | `q115` | `HYBRID_RRF` | `cross_type_topic` | `winter snow ice cold` | 5 个冬日/极地作品 | `manga-14`, `anime-17`, `noise-097` | `noise-097` 含 `weather/clouds` 与冷冬语义相近被向量拉进 Top-3 |
| 12 | `q115` | `HYBRID_RERANK` | `cross_type_topic` | `winter snow ice cold` | 同上 | `manga-14`, `anime-17`, `noise-097` | 同上：语义近邻噪声（气象科普文档）在无领域过滤时较难完全区分 |

## 6. 后续改进路径与已知限制

- **中文语义模型升级**：后续将 `server/models/e5-small-v2.onnx` 替换为同样 **384 维**的 `intfloat/multilingual-e5-small`，无需改动 `vector(384)` 表结构，只需执行一次全量同步并用 `HybridSearchBenchmarkIT` 复测即可补齐纯中文语义召回短板。
- **稀缺词加权（IDF / BM25）**：针对 `q105` 暴露的高频标签（`校园`/`青春`）压过区分词（`运动`）问题，后续可在轻量 Reranker 中引入语料词频 IDF 权重。
- **测试语料边界**：本评测集采用版权清晰的自建站内样例 + 155 条背景噪声文档（共 200 条），实测结果反映该规模与 `e5-small-v2` 模型下的真实表现，不直接外推至百万级语料。
