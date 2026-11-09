# 提案：M4 智能检索 —— OCR、字面检索与语义召回

## Why

M3 完成了内容闭环，但"能检索"还停留在文件名层面（DESIGN 2.1 目标 4）。M4 交付：
章节页级 OCR 索引（RapidOCR 本地任务，无模型费用）→ 书名/章节名 + 对白字面检索
（pg_trgm）→ 本地 ONNX Embedding（e5-small-v2，384 维，经 hf-mirror 实测下载）
的语义召回 → 混合排序 → 页级定位与"有动画"过滤；问答机制（证据 ID + SSE）落地，
无对话模型时按 DESIGN 19 明确标记未配置（503），不静默降级。索引任务由所有者
显式触发（DESIGN 15.1），OCR 无费用、Embedding 为本地推理零费用。

## What Changes

- 迁移 V5：index_generations（批次状态 BUILDING→READY→ACTIVE，DESIGN 9/15.5）、
  search_chunks（chunk_kind=text，含来源引用与 tsvector/trgm 索引）；jobs 新增
  index.build 种类，RabbitMQ 增加 content.index 队列（DESIGN 11.1 的第二类队列）。
- 迁移 V6（e5 本机实测产出 384 维向量后）：search_chunks 增加 embedding vector(384)
  列与 HNSW 索引（维度经真实嵌入验证才落库，DESIGN 8.2/24.1-5）。
- `processing/`：`chapter.ocr` 任务——RapidOCR 页级识别（中英文实测可用），
  输出逐页文本清单；本地推理无模型费用。
- `ingestion/`：IndexBuildHandler（content.index 队列）——下载页面 → OCR →
  写 search_chunks → 批次 READY；批次完整后原子 ACTIVE（DESIGN 15.5）。
- 新增 `retrieval/`：POST /search（标题 + OCR 两路召回 + RRF 合并 + 授权范围 +
  "有动画"过滤）、POST /chapter-revisions/{id}/indexes（所有者显式触发）、
  POST /qa/answers（证据 ID + SSE + 无对话模型时 503 QA_NOT_CONFIGURED）。
- Spring AI 2.0.2 transformers（本地 e5-small-v2 ONNX）作为 EmbeddingModel；
  问答的 ChatClient 无可用渠道（本机无 LLM、不引入付费渠道），按未配置处理。
- `web/`：搜索页（标题/对白结果、页级定位链接、"有动画"过滤）、问答入口
  （未配置时禁用并说明）。
- 评估：固定演示素材 + 小规模标注查询集（15 条）实测 Recall@K，记录规模边界
  （设计 21.2 的 30–50 条为完整目标，本阶段以可复现脚本交付小集）。

## Capabilities

### New

- `content-retrieval`：页级 OCR 索引、字面与语义召回、搜索结果与问答引用。

## Impact

- 影响：迁移 V5/V6、processing 新任务、retrieval 模块、队列拓扑（+content.index）、
  前端搜索页。
- 不影响：接入协议（search:read scope 已预留，M4 搜索端点对 Bearer 开放）。
- 风险：RapidOCR 对漫画字体的召回率有限（页级定位足够，单格精排后置，DESIGN 15.2）；
  e5 对中日文的语义效果需在评估集中观察；问答模型未配置期间 QA 为 503（明确可见）。
