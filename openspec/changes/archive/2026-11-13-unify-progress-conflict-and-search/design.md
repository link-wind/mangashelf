## Context

动机见 proposal.md。依据 docs/distributed-evolution.md 4.3（语义检索可选）与 4.4（进度冲突统一），设计基线为 DESIGN.md 第 15、20 节。

当前实现事实（2026-11-13 核验）：

- `SearchService.search()` 每次调用都执行 `embedding.embed()`，无模式开关；`SearchController.SearchRequest` 只声明 `withAnimation`，前端发 `hasAnimation`。
- 本地阅读（`reading_progress`）与本地观看（`watch_progress`）已有 `version` 列与 409 `PROGRESS_CONFLICT`（`details.current`）；`LibraryService`、`AnimationService` 是既有范式。
- 外部漫画（`external_manga_progress`）与外部观看（`external_watch_progress`）无 `version`，`ON CONFLICT DO UPDATE` 无条件覆盖。
- 外部观看进度键为 `(user_id, work_id, ordinal)`，只返回列表，无单条读取；`/me/watch-history` 与 `/me/continue-watching` 是同一实现。
- `BrowseWatch`（浏览态）当前不写任何进度（属于下一个变更）。

## Goals / Non-Goals

**Goals:**

- 搜索默认不依赖模型，语义检索成为显式且可预期失败的模式。
- 四类进度写入共享一套版本冲突契约与服务端快照。
- 前端不会因并发写入破坏新进度；冲突可见且可恢复。

**Non-Goals:**

- 不改收藏、历史、续看的数据归属与页面结构（下一个变更）。
- 不合并 `watch-history` 与 `continue-watching` 的语义（下一个变更）。
- 不为浏览态播放新增进度（依赖下一变更的进度键设计）。
- 不引入通用"写入队列"抽象或新依赖。

## Decisions

1. **搜索契约：`mode` 枚举，默认 `title`；不保留 `hasAnimation` 别名。**
   `POST /api/v1/search` 请求体为 `{q, mode?: "title"|"semantic", withAnimation?, limit?}`。
   `title` 模式不构造查询向量；`semantic` 模式在模型不可用时返回 503 `SEMANTIC_UNAVAILABLE`（与既有规格一致）。
   响应保留 `semanticEnabled` 作为"模型是否可用"的信号，前端据此决定是否展示语义开关。
   备选：后端同时接受 `hasAnimation` 别名——会让契约继续模糊，前端已同步发布，无需兼容。

2. **进度版本算法：先条件更新，`expectedVersion=0` 时回退插入。**
   外部漫画与外部观看统一：
   - `expectedVersion > 0`：`UPDATE ... SET version = version + 1 WHERE 主键 AND version = ? RETURNING version`；0 行即冲突。
   - `expectedVersion = 0`：`INSERT ... ON CONFLICT DO NOTHING RETURNING version`；未插入说明并发已创建，读取快照后返回 409。
   冲突响应复用既有结构：`ApiException(409, "PROGRESS_CONFLICT", message, {current: 快照})`，与 `LibraryService` 完全一致。
   备选：客户端只发"上次读取的 updated_at"——时间戳精度与时钟不可靠，版本号更明确。

3. **新增单条读取端点：`GET /api/v1/me/external-progress?workId=&ordinal=`。**
   无记录时返回 `{version: 0, positionMs: 0, ...}` 而不是 404，客户端可直接把 `version` 用作首次写入的 `expectedVersion`，无需特判错误码。

4. **前端串行化写入：单飞行 + 最新值合并。**
   阅读器与播放器各自维护 `versionRef` 与"待写位置"：
   - 同一时刻最多一个写请求；期间的新位置只覆盖待写值，响应后补写。
   - 成功后 `versionRef = 响应.version`；409 时从 `details.current.version` 更新，并展示非阻塞提示（"其他设备已更新进度"）。
   - 冲突处理**不**在播放/滚动中强制跳转位置：当前设备的继续阅读/播放被视为最新活动，下一次成功写入会推进版本；服务端快照只用于提示与版本对齐。
   备选：冲突时强制 seek 到服务端位置——会打断正在进行的阅读/播放，体验更差。

5. **迁移 V23 只加列，不改数据。**
   `ALTER TABLE ... ADD COLUMN version int NOT NULL DEFAULT 1;` 存量行版本为 1，客户端列表拿到 1 即可正常写入。
   备选：新建表——无必要，主键与语义不变。

6. **列表响应增加 `version`，字段可选不破坏旧字段。**
   `GET /me/manga-progress`、`/me/watch-history`、`/me/continue-watching` 的行记录追加 `version`；其余字段与顺序不变。

## Risks / Trade-offs

- [旧客户端未带 `expectedVersion` 会 400] → 前后端同仓库同步发布，`web` 与 `api` 一起构建；验证场景覆盖首次写入与缺省请求。
- [播放器高频写导致互相冲突] → 串行化 + 最新值合并，单设备最多一个在途请求；只有真正多设备并发才会 409。
- [搜索语义模式 503 影响现有搜索体验] → 默认模式为 `title`，语义开关仅在 `semanticEnabled` 时展示；既有搜索结果不受模型影响。
- [外部漫画冲突提示过多] → 仅在 409 时提示，且同类冲突 30 秒内只提示一次（前端节流），避免滚动连续冲突刷屏。

## Migration Plan

1. Flyway V23 加列（API 启动时执行，单机 compose 顺序发布）。
2. 后端与前端同一构建发布；先部署 API 再刷新前端不影响，API 对外契约已要求版本。
3. 回滚：代码回滚 + V23 保留（加列可向后兼容，旧代码忽略该列）。

## Open Questions

无。浏览态播放进度与收藏/历史拆分已明确留给下一个变更。
