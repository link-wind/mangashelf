# 任务：M4 智能检索

## 迁移与队列

- [x] 迁移 V5：index_generations（批次/模型/维度/manifest_sha256/状态）、
      search_chunks（chunk_kind、text、来源引用、trgm+GIN 索引）
- [x] jobs 新增 index.build 种类（目标 chapter_revision_id）+ CHECK 更新
- [x] RabbitMQ content.index 队列声明与绑定（DESIGN 11.1 第二类队列）

## OCR 与索引构建

- [x] Python chapter.ocr：RapidOCR 页级识别（中英文实测），逐页 {ordinal, text}，
      空页如实输出空串；stdout 仅 JSONL
- [x] POST /chapter-revisions/{id}/indexes：所有者显式触发 → 批次 BUILDING + index.build 任务
- [x] IndexBuildHandler（content.index）：下载页面 → OCR → search_chunks 落库 →
      批次 READY 且同事务 ACTIVE（含 source_manifest_sha256 核对）
- [x] 重复触发幂等：同版本已有 ACTIVE 批次时明确报错或重建（选型记录）

## 检索

- [x] POST /search：标题分支（works/chapters ILIKE + similarity）+ OCR 分支
      （trgm % + similarity）→ RRF 合并去重；SQL 先行授权过滤（DESIGN 3.3）
- [x] 结果：work/chapter/page 定位、片段摘要、hasAnimation 过滤（页级关联查询）
- [x] 索引仅对 PUBLISHED 版本召回；换版后旧版本索引不与新版本混排（15.3）
- [x] search:read scope（Bearer）；浏览器会话可用

## 语义与问答

- [x] Spring AI transformers + 本地 e5-small-v2：实测产出向量并记录维度
- [x] V6：search_chunks.embedding vector(实测维度) + HNSW；批次记录 embedding_model/dimensions
- [x] 语义分支：查询向量召回 + 与字面 RRF 合并；未配置模型时入口明确 503/禁用
- [x] POST /qa/answers：证据 ID（S1…）+ 素材文本不可信提示 + ChatClient 流式 SSE；
      无对话模型 → 503 QA_NOT_CONFIGURED

## 前端与评估

- [x] 搜索页：结果分类（作品/页）、页级定位链接、"有动画"过滤、问答入口（未配置禁用）
- [x] 评估：固定演示素材 + 15 条标注查询 → Recall@K 脚本与结果记录（规模边界注明）
- [x] docs/validation/m4.md 写实测结果与已知限制；README 里程碑状态更新
