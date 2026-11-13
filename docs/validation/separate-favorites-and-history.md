# separate-favorites-and-history 验证记录

- 日期：2026-11-13
- 基线：`main` `1be9f27` + 本变更工作区改动
- 环境：本地打包 jar（`--mangashelf.role=api --server.port=18081`）连 compose PostgreSQL（Flyway 应用 V24）；vite dev 代理到 18081 + IAB 浏览器

## 验证场景与结果

| 场景 | 结果 |
| --- | --- |
| V24 迁移 | `favorites`（CHECK + 三部分唯一索引）与 `catalog_watch_progress` 建表成功；`library_entries` 数据复制后删除（本库无存量收藏，表已不存在） |
| 三类收藏 | PUT work/manga/catalog 均 204；重复收藏幂等（204，条目不增）；不存在目录条目 → 404 `ITEM_NOT_FOUND` |
| 缺必填参数 | `PUT /me/favorites/manga` 缺 source → **400** `INVALID_ARGUMENT`（新增 `MissingServletRequestParameterException` 处理器，见 design 追记） |
| 收藏列表 | 统一响应三类型混合、按收藏时间倒序；类型字段可区分；外部漫画标题用收藏快照 |
| 取消收藏 | 目录条目与作品取消后列表移除；历史与继续观看不受影响（继续观看仍 5 条） |
| 进度按集路由 | 物化剧集经 `COALESCE(episode.catalog_item_id, item.id)` 解析到目录条目，`targetKind=catalog`；首次写入 0 → version 1；重复 0 → **409** 快照 version 正确；推进 1 → version 2 |
| 目录进度表 | 写入落在 `catalog_watch_progress`（catalog_item_id + ordinal 键） |
| 导入前后进度稳定 | 收编清空 `catalog_item_id` 后，同一剧集经作品反查目录条目，进度键不变（见 design 追记：DB CHECK 要求二者互斥，不能同时保留） |
| 缺 expectedVersion | → 400 `PROGRESS_INVALID`；不存在剧集 → 404 `EPISODE_NOT_FOUND` |
| 历史聚合 | `/me/history` 覆盖外部观看（含浏览态）、漫画阅读、作品观看，按时间倒序 |
| 继续观看聚合 | `/me/continue` 每个目标只一行；浏览态条目以 `catalogItemId + episodeId` 为键，作品态以 `workId + episodeId` |
| 浏览态播放进历史 | 时刻表点击播放（新番条目）→ 浏览态页播放；作品数不变（4 → 4）；进度落 `catalog_watch_progress` |
| 时刻表去物化 | 页面无「加入书库」按钮；播放按钮直接跳 `/browse/{itemId}/{episodeId}` |
| 收藏页 | 只显示收藏（漫画 2 / 动漫 1），无阅读/观看记录区块；取消收藏按钮可用 |
| 历史页 | 统一历史（阅读 + 观看分组），浏览态条目「继续观看」链接到 `/browse/...` |
| 共享播放 hook | `useEpisodePlayback` 供两页复用；`ExternalWatch` 与 `BrowseWatch` 均改用统一 `/me/episode-progress` |
| 后端测试 | 43/43 通过 |
| 前端构建 | tsc + vite 通过 |
| Python 测试 | 通过（本变更未触及 processing） |

## 实施追记（与 design.md 差异）

- **进度键稳定改为作品反查**：`external_episodes` 存在 CHECK `ext_ep_owner_check`（work_id 与
  catalog_item_id 互斥），收编剧集必须清空 catalog_item_id。因此 `episodeTarget` 改为
  `COALESCE(e.catalog_item_id, c.id WHERE c.work_id = e.work_id)`；已在 design.md 决策 3 更新。
- **新增缺参处理器**：`MissingServletRequestParameterException` → 400（此前落入通用 500）。

## 已知限制

- 导入（materialize）仅验证"播放不创建作品"路径与幂等语义（复用上一变更已验证的服务端逻辑）；「导入为我的作品」按钮的浏览器路径未端到端走完（目录条目已物化，无未物化样本可用）。
- 本地阅读/本地动画的进度沿用既有端点，未重新回归（上一变更已覆盖）。
- 验证库中留有 `fav_smoke` 测试账号及其私有收藏/进度数据。
- 首页"继续观看"与"继续阅读"改用 `/me/continue` 后经构建与接口验证，未逐卡点浏览器点击。
