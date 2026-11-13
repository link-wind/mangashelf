## Why

docs/distributed-evolution.md（第 10 节变更序列之 2 的第一批）指出两类必须优先修正的语义问题：

1. 全站搜索前端发送 `hasAnimation`、后端只读取 `withAnimation`，"只看有动画"筛选从未生效；同时语义分支在每次搜索时同步调用 Embedding 模型，普通标题检索被模型耗时拖住，且模型不可用时行为与规格（503）不符。
2. 四类进度写入行为不一致：本地阅读与本地观看有 `expectedVersion` 版本冲突控制，外部漫画与外部观看则无条件覆盖；多设备同时观看时，后到的旧请求会静默覆盖新进度。

这批改动小、风险低，先收口搜索与进度的语义，再单独处理收藏、历史与播放归属（4.4 其余部分）。

## What Changes

- 搜索请求增加显式 `mode`：`title`（默认）与 `semantic`。`title` 模式 MUST NOT 调用 Embedding 模型；`semantic` 模式在模型未配置时按既有规格返回 503，不再静默降级。
- 前端搜索修正筛选参数名为 `withAnimation`；语义检索改为显式开关（仅在模型可用时展示）。
- `external_manga_progress`、`external_watch_progress` 增加 `version` 列（迁移 V23）；写入要求 `expectedVersion`，匹配才更新并递增，不匹配返回 409 `PROGRESS_CONFLICT` 与当前快照，与本地阅读/观看一致。
- 四类进度的冲突响应统一为 `{code: "PROGRESS_CONFLICT", details: {current: 快照}}`。
- `GET /api/v1/me/manga-progress`、`/me/watch-history`、`/me/continue-watching` 返回 `version`，供客户端续读时携带；新增按 `(workId, ordinal)` 读取外部观看进度的端点。
- 前端阅读器与播放器串行化进度写入：保存成功后更新本地版本号；收到 409 时采纳服务端快照并提示，不静默覆盖。
- **BREAKING**：`PUT /api/v1/me/manga-progress` 与 `PUT /api/v1/external-progress` 要求 `expectedVersion`（首次写入传 0），缺省返回 400。自托管单部署、前后端同步发布，不做多版本兼容。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `content-retrieval`：新增"搜索模式显式区分"要求（title 默认不跑模型、semantic 需模型、动画筛选两种模式均生效）。
- `library-progress`：新增"进度写入统一版本冲突语义"要求，覆盖本地阅读、本地观看、外部漫画、外部观看四类进度。
- `manga-sources`：外部漫画进度保存要求增加版本冲突与快照返回。
- `external-animations`：外部集播放进度要求增加版本冲突与快照返回。

## Impact

- 数据库：`server/src/main/resources/db/migration/V23__unify_progress_versions.sql`（两表加列，默认 1）
- 后端：`SearchController`/`SearchService`、`MangaProgressController`、`ExternalController`/`ExternalService`/`ExternalRepository`
- 前端：`Search.tsx`、`ExternalMangaReader.tsx`、`BrowseWatch.tsx`、`ExternalWatch.tsx`
- 无新依赖；无队列与部署变更
