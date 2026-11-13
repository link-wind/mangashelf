## Why

docs/distributed-evolution.md 4.4 的首要问题：收藏、历史与继续观看混在一起。当前「我的收藏」把本地作品收藏、外部漫画阅读记录和动漫观看历史放进同一页；动漫标签读取的是 `watch-history`，外部漫画读取 `manga-progress`。浏览过的内容会出现在收藏页却没有收藏关系与取消操作。同时时刻表点击播放仍调用 `materialize` 创建用户作品，把公共目录条目与个人所有权耦合；浏览态播放（未物化）没有进度，无法续播与进入历史。

## What Changes

- 新增统一收藏关系：本地作品、外部漫画（源 + 作品标识）、外部番剧目录条目三类目标；数据库用明确外键/约束，不做通用资源表；重复收藏幂等。
- **BREAKING** `/api/v1/me/library` 收藏接口退役，改为 `/api/v1/me/favorites/{work|manga|catalog}/...`；迁移既有 `library_entries` 数据后删除该表。
- 收藏列表统一响应（类型、标题、封面、目标标识、收藏时间），前端「我的收藏」只展示收藏，不再混入阅读/观看记录。
- 新增历史记录接口：合并本地阅读、外部漫画阅读、本地观看、外部番剧观看（含未收藏的浏览态播放），按最近活动倒序。
- 新增继续阅读/观看接口：按作品或目录条目聚合，每个目标只返回最近可恢复位置。
- 新增浏览态播放进度（按目录条目 + 集号，带版本冲突语义），物化前后进度键保持稳定。
- 时刻表与目录详情的播放改为直接浏览态播放，不再创建作品；物化入口仅保留为显式「导入为我的作品」。
- 外部漫画详情、目录详情、浏览播放页提供收藏/取消收藏操作；本地作品详情沿用收藏按钮并切换到新接口。
- 抽出浏览态与作品态播放共用的解析、选集与进度逻辑，消除 `BrowseWatch`/`ExternalWatch` 重复实现。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `library-progress`：收藏要求扩展为三类目标并新增统一收藏列表；新增历史记录与继续观看分离要求。
- `external-animations`：目录条目点播不再物化；新增浏览态播放进度与物化前后进度稳定要求；物化改为显式导入。
- `external-manga`：外部漫画收藏与阅读记录分离（收藏进入统一收藏，阅读记录进入历史）。

## Impact

- 数据库：V24 迁移（新建 `favorites`、`catalog_watch_progress`；迁移并删除 `library_entries`）
- 后端：`library`（收藏仓储/服务/控制器重写）、`external`（浏览态进度、历史/续看聚合、显式导入）、`catalog`（目录条目收藏展示）
- 前端：`Shelf.tsx`（只保留收藏）、`History.tsx`（统一历史）、`Home.tsx`、`WorkDetail.tsx`、`MangaDetail.tsx`、`ExternalMangaDetail.tsx`、`CatalogDetail.tsx`、`Schedule.tsx`、`BrowseWatch.tsx`、`ExternalWatch.tsx` + 共享播放逻辑
- 兼容：收藏接口 BREAKING，前后端同步发布；进度接口沿用既有版本冲突契约
- 无新依赖；无队列与部署变更
