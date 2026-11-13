## 1. 数据库与搜索后端

- [x] 1.1 新增 `V23__unify_progress_versions.sql`：`external_manga_progress`、`external_watch_progress` 各加 `version int NOT NULL DEFAULT 1`
- [x] 1.2 `SearchController.SearchRequest` 增加 `mode`（`title|semantic`，缺省 `title`）；`SearchService.search` 仅在 `semantic` 模式计算向量，模型不可用返回 503 `SEMANTIC_UNAVAILABLE`
- [x] 1.3 真实环境验证：默认模式搜索不触发模型日志、`semantic` 模式在无模型时 503、`withAnimation` 筛选生效

## 2. 外部漫画进度版本化

- [x] 2.1 `MangaProgressController.save` 改为条件更新 + 首次插入（`expectedVersion=0`），冲突返回 409 `PROGRESS_CONFLICT` 与 `details.current`
- [x] 2.2 `GET /me/manga-progress` 返回 `version`；`expectedVersion` 缺省返回 400
- [x] 2.3 前端 `ExternalMangaReader`：维护版本号、单飞行写入、409 更新版本并节流提示

## 3. 外部观看进度版本化

- [x] 3.1 `ExternalRepository.upsertProgress` 改为版本条件更新；`ExternalService.saveProgress` 冲突返回 409 与快照
- [x] 3.2 新增 `GET /api/v1/me/external-progress?workId=&ordinal=`，无记录返回 `version=0` 快照；历史/续看列表返回 `version`
- [x] 3.3 前端 `ExternalWatch`：从单条端点取版本、串行化写入、409 节流提示

## 4. 验证与收尾

- [x] 4.1 真实 PostgreSQL 场景：同一 `expectedVersion` 两次写入，第二次 409 且快照正确；首次写入得到版本 1；缺省版本 400
- [x] 4.2 浏览器验证：搜索默认不跑模型、语义开关按 `semanticEnabled` 展示；阅读器与播放器进度正常保存、冲突时提示可见
- [x] 4.3 `mvnw test`、Python 套件、`npm run build` 全部通过
- [x] 4.4 验证结果写入 `docs/validation/unify-progress-conflict-and-search.md`
