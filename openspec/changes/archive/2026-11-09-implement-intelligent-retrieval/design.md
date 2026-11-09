# 设计：M4 智能检索

## Context

DESIGN 15（先搜索后问答）、8.2（search_chunks/index_generations）、9（批次状态）、
11.1（content.index 队列）、16.1（端点）、21.2（评估集）为依据。M3 已建立：
任务模型（双队列前是单队列）、scope 体系（search:read 已定义）、对象存储访问。
本机实测（2026-11-08）：RapidOCR 中英文识别可用（模型内置缓存）；e5-small-v2
ONNX + tokenizer 经 hf-mirror 下载成功（server/models/，133MB）；本机无 LLM
（无 Ollama/LM Studio），HF 直连不可达、镜像可达。

## Goals / Non-Goals

**Goals**

- 页级 OCR 索引：所有者显式触发，RapidOCR 本地推理，批次原子激活。
- 字面检索：标题（作品/章节）+ OCR 对白子串（pg_trgm），授权范围内召回，
  页级定位链接与"有动画"过滤。
- 语义召回：e5-small-v2（384 维）本地嵌入，向量列与 HNSW 在实测产出向量后经
  V6 迁移落地；索引批次记录模型与维度。
- 问答机制：检索结果 → 服务端证据 ID（S1…）→ SSE 流式回答；无对话模型 → 503
  QA_NOT_CONFIGURED（明确可见，不伪造回答）。
- 评估：小规模标注查询集 + Recall@K 脚本，结果与规模边界记录。

**Non-Goals**

- 单格级/片段级索引（DESIGN 15.2：页级验收后再加）；重排模型；多路复杂排序。
- 付费 Embedding/对话渠道；不自动换模型（DESIGN 19）。
- 问答的数据库写入、发布或任意工具执行权限（DESIGN 15.4）。

## Decisions

### D1：维度先行验证再落库（DESIGN 8.2/24.1-5）

V5 只建 search_chunks 文本结构与 index_generations（无向量列）。实现 e5 嵌入后：
Spring AI transformers 加载本地 ONNX 产出向量 → 实测维度 384 → V6 迁移
`ALTER TABLE search_chunks ADD COLUMN embedding vector(384)` + HNSW 索引。
"首个已验证模型固定维度"按字面执行；换模型 = 新迁移 + 全量重建批次。

### D2：双队列落地（DESIGN 11.1）

content.index 队列承接 index.build 任务（目标 chapter_revision_id）；
content.process 不变。队列声明在 RabbitTopologyConfig 扩展，路由键同名。
索引并发由消费端 prefetch=1 串行（单 Worker），满足"模型/索引并发独立控制"。

### D3：页级 OCR 任务独立于章节解析（DESIGN 15.2）

`chapter.ocr` 任务输入为页面清单（对象 Key + 页序，Java 预下载到任务目录），
RapidOCR 逐页识别 → result.json 输出 [{ordinal, text}]。单页无文字如实输出空串。
索引批次 BUILDING→READY 由 Handler 提交；ACTIVE 在批次完整落库的同一事务内置位
（首版无"多批次并存"，重建 = 新批次 READY 即换 ACTIVE，旧批次保留供审计）。

### D4：字面召回用 pg_trgm 相似度排序（DESIGN 15.3）

标题分支：works.title/chapters.title ILIKE + similarity() 排序；
OCR 分支：search_chunks.text % q（trgm）+ similarity 排序。中文效果按 21.2 评估集
实测记录（trgm 对中文按字 trigram，短语命中可用，长句语义不可用——由语义分支承担）。
两路结果 RRF 合并（k=60），按 (work, chapter, page) 去重，必要时展开同章前页。

### D5：授权范围前置（DESIGN 3.3/15.3）

两路召回的 SQL 一律先连 works/chapters 过滤（owner 或 PUBLIC+PUBLISHED），
不存在"先检索全库再前端隐藏"。结果返回前不二次校验（SQL 已限定），但页级定位
链接复用 requireReadableRevision 语义（版本非 PUBLISHED 的索引块不召回——
索引只对 PUBLISHED 版本构建）。

### D6：问答机制与门控（DESIGN 15.4/19）

POST /qa/answers {question}：检索 top-N 证据 → 服务端分配证据 ID（S1…Sn）→
构造提示（素材文本不可信，只允许引用证据 ID）→ ChatClient 流式 → SSE
（token 事件 + 最终引用事件）。本机无对话模型：ChatClient bean 条件装配缺失时
端点返回 503 QA_NOT_CONFIGURED（错误信息明确"对话模型未配置"）。
证据 ID 映射只在服务端；模型声称的无效 ID 被丢弃，证据不足时返回
"当前素材无法确认"。

### D7：Embedding 经 Spring AI transformers 本地推理（回应 24.1-5）

spring-ai-starter-model-transformers（2.0.x）+ 本地 e5-small-v2 ONNX/tokenizer
（file: URI，模型文件不入库——models/ 已 gitignore）。e5 约定：query 前缀
"query: "、passage 前缀 "passage: "。索引嵌入与查询嵌入同模型同维度；
批次记录 embedding_model="e5-small-v2"、dimensions=384、pipeline_version。

## Risks / Trade-offs

- trgm 对中文按字 trigram：短词命中好、长句语义弱——语义分支补位，评估集观察。
- RapidOCR 漫画字体召回有限：页级定位可接受，单格精排后置（15.2）。
- e5 对中日文效果未知：评估集记录定性结论；不承诺泛化（21.2）。
- QA 无对话模型期间为 503：机制完整（证据/ID/SSE），配置渠道后即可启用。

## Migration Plan

V5（文本索引结构）→ 实现与实测 → V6（向量列 + HNSW）→ 语义分支激活。
两迁移均为前向追加。

## Open Questions

- e5 在 Spring AI transformers 下的 tokenizer/池化输出是否即用（实现时验证，
  输出维度若非 384 以实测为准并同步 V6）。
