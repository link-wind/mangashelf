# unify-progress-conflict-and-search 验证记录

- 日期：2026-11-13
- 基线：`main` `8b88278` + 本变更工作区改动
- 环境：本地打包 jar（`--mangashelf.role=api --server.port=18081`）连 compose PostgreSQL（Flyway 自动应用 V23）/RabbitMQ；前端 vite dev 代理到 18081 + IAB 浏览器

## 验证场景与结果

| 场景 | 结果 |
| --- | --- |
| V23 迁移 | 启动日志 `Migrating schema to version 23 - unify progress versions`，`Successfully applied 1 migration`；存量外部漫画进度行自动获得 `version = 1` |
| 搜索默认模式 | `POST /api/v1/search {q:"海"}` → 200，`mode=title`、`semanticEnabled=true`，耗时 43ms |
| 搜索语义模式 | `{q:"海", mode:"semantic"}` → 200，`mode=semantic`，耗时 63ms（模型可用） |
| 模型缺失 + 语义 | 以 `--mangashelf.embedding.enabled=false` 启动：title → 200 `semanticEnabled=false`；semantic → **503** `SEMANTIC_UNAVAILABLE`，字面检索不受影响 |
| 非法模式 | `{mode:"fuzzy"}` → **400** `INVALID_ARGUMENT` |
| 标题模式不触达模型 | 单元测试 `titleModeNeverCallsEmbeddingModel`：Embedding 模型被调用即抛错，标题搜索仍成功；`semanticModeWithoutModelFailsExplicitly` 校验 503 |
| 有动画筛选 | 单元测试 `hasAnimationFiltersWorkHits`：两作品一个有已发布动画，`withAnimation=true` 仅返回带动画者，`false` 返回两条 |
| 漫画进度首次写入 | `expectedVersion=0` → 200，`version=1` |
| 漫画进度并发冲突 | 再次 `expectedVersion=0` → **409** `PROGRESS_CONFLICT`，`details.current.version=1`，已保存进度未变 |
| 漫画进度正常递增 | `expectedVersion=1` → 200，`version=2` |
| 漫画进度缺版本 | 不传 `expectedVersion` → **400** `PROGRESS_INVALID` |
| 漫画进度列表 | `GET /me/manga-progress` 每行含 `version`（含迁移回填的存量行） |
| 观看进度无记录 | `GET /me/external-progress?workId=&ordinal=` → `version=0, positionMs=0` 快照 |
| 观看进度首次写入 | `expectedVersion=0` → 200，`version=1` |
| 观看进度并发冲突 | 再次 `expectedVersion=0` → **409**，`details.current.version=1, positionMs=120000` |
| 观看进度正常递增 | `expectedVersion=1` → 200，`version=2` |
| 观看进度缺版本 | → **400** `PROGRESS_INVALID` |
| 观看历史列表 | `GET /me/watch-history` 行含 `version` |
| 浏览器：搜索页 | 两个筛选可见（"只看有动画的"、"语义检索（较慢）"）；勾选语义检索后请求 200，无错误提示 |
| 浏览器：阅读器冲突 | MangaDex 章节打开后，服务端版本被另一设备推进到 4；阅读器翻页保存收到 409 → 显示"其他设备已更新进度，已对齐版本继续保存"；继续翻页后保存成功，服务端页码 8、版本 7 |
| 浏览器：播放器冲突 | 物化"欢迎光临"目录条目后播放（量子资源 HLS，解析成功）；观看进度写入 `version=1`；curl 模拟另一设备推进到 `version=2` 后触发播放器进度事件 → 显示"其他设备已更新进度，已对齐版本继续保存"；再次触发后写入成功，服务端 `version=3, positionMs=12000` |
| 后端测试 | `mvnw test`：**43/43** 通过（新增 `SearchServiceTest` 3 项） |
| 前端构建 | `npm run build`（tsc + vite）通过 |
| Python 测试 | 1/1 通过（本变更未触及 processing） |

## 已知限制

- "有动画"筛选用单元测试验证逻辑；真实数据库无"已索引且带动画"的内容，未做页面级端到端筛选验证。前端参数名已改为 `withAnimation` 并与后端契约一致。
- 播放器端到端验证使用了旧有的"目录条目物化"入口（`POST /catalog-items/{id}/materialize`）创建测试作品；该入口将在下一个变更（收藏/历史分离与播放去物化）中调整，不影响本变更的进度语义。
- 验证在开发库留下了 `retire_smoke` 账号、两个私有草稿作品、一个物化的外部作品（"欢迎光临"）与测试进度行，均为该账号私有数据，不影响其他账号。
- 冲突提示 30 秒节流为前端行为，未在浏览器中重复冲突验证节流。
- 播放器调试时视频流停留在首帧（CDN 未继续加载），进度事件通过派发 `timeupdate` 验证；真实播放中的自动上报沿用同一回调路径。
