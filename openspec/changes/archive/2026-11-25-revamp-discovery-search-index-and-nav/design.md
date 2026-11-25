# Design: 导航精简、全流漫画搜索、分类分页索引与双端推荐优化

## 1. 后端模块与接口设计

### 1.1 漫画源健康熔断与全流聚合搜索（`com.mangashelf.external.manga`）

- **轻量熔断器（`MangaSourceHealth` 或内嵌于 `MangaDiscoveryService`）**：
  - 使用 `ConcurrentHashMap<String, SourceCircuit>` 记录每个源的连续失败次数、冷却截止时间（`cooldownUntil`）与最后错误原因。
  - 规则：连续失败达到 2 次进入 5 分钟冷却；冷却期内发现流与聚合搜索直接跳过该源并在 `errors` / `sourceStatus` 中返回友好提示，避免卡死 5–20 秒；冷却到期后自动允许探测恢复，成功即清零。
- **全流聚合搜索 `GET /api/v1/manga-discovery/search`**：
  - 参数：`q`（必填）、`source`（可选，默认 `all`）、`limit`（默认 30，上限 60）。
  - 执行链路：
    1. 虚拟线程并行查询：
       - 所有支持 `Capability.SEARCH` 的中文源（单源硬超时 5 秒，受熔断器保护）；
       - `manga_catalog_items` 目录表（覆盖无 `SEARCH` 接口的 `bilibili` 与历史沉淀条目）；
       - 站内 `works` 表（公开或当前登录用户有权限的本地漫画作品，不越权召回）。
    2. 实时命中的外部条目异步沉淀入 `manga_catalog_items`（不阻塞搜索响应）。
    3. 复用 `MangaDiscoveryService.normalizeTitle` + `MangaMatcher.similarity >= 0.9` 做跨流聚簇合并：
       - 每条聚合结果包含：`title`、`altTitle`、`coverUrl`、`primarySource`、`primaryId`、`workId`（若含本地作品）、`readable`、`remarks`、`sourceCount`、`streams`（`List<StreamEntry>`：`source`、`sourceName`、`id`、`readable`、`coverUrl`、`remarks`）。
       - 代表源选择优先级：本地作品 / 站内可读源 > 标题精确匹配 > 有封面源。
    4. 排序：标题精确匹配 > 标题前缀匹配 > 多源共识数 > 站内可读 > 相似度得分。
    5. 返回 `{ query, items, sourceStatus }`，其中 `sourceStatus` 记录各流的 `source`、`name`、`count`、`ok`、`error`、`durationMs`。

### 1.2 番剧与漫画多维分类 + 页码分页

- **番剧目录 `GET /api/v1/catalog-items`（[ExternalCatalogRepository.java](file:///Users/linkwind/Code/mangashelf/server/src/main/java/com/mangashelf/external/ExternalCatalogRepository.java) / [ExternalCatalogService.java](file:///Users/linkwind/Code/mangashelf/server/src/main/java/com/mangashelf/external/ExternalCatalogService.java)）**：
  - 新增可选参数：`year`（如 `2026`、`2025`、`2024`、`2023`、`earlier`），`sort` 支持 `updated`（默认）、`rating`（按 `bangumi_subjects.rating DESC NULLS LAST`）、`episodes`。
  - 修复 `count(...)` 遗漏 `q` 参数的问题，统一筛选构建方法 `appendWhere(...)` 供 `page()` 与 `count()` 共用。
  - 统一分页语义：
    - 若传入 1-based `page`（`page >= 1`）或 `sort` 为 `episodes`/`rating`，使用 `OFFSET (page - 1) * limit LIMIT limit`；
    - 返回 `CatalogItemPage(items, nextCursor, total, page, limit, totalPages)`（向下兼容既有 `nextCursor` 与 `total` 字段）。
  - 新增 `GET /api/v1/catalog-items/genres`：聚合已关联可播条目的 `bangumi_subjects.tags` Top 24 热门题材（带 5 分钟热点缓存），过滤明显无意义的年份/地区噪声标签。
- **漫画目录 `GET /api/v1/manga-catalog`（[MangaCatalogRepository.java](file:///Users/linkwind/Code/mangashelf/server/src/main/java/com/mangashelf/external/manga/MangaCatalogRepository.java) / [MangaCatalogController.java](file:///Users/linkwind/Code/mangashelf/server/src/main/java/com/mangashelf/external/manga/MangaCatalogController.java)）**：
  - `source` 改为可选（为空或 `all` 时查全流）；
  - 新增 `sort`（`updated` / `episodes`）与 1-based `page` 参数，返回 `CatalogPage(items, nextCursor, total, page, limit, totalPages)`；
  - `GET /api/v1/manga-catalog/genres` 将 `source` 改为可选，不传时聚合全流热门题材；
  - 修复 [MangaCatalogSyncHandler.java](file:///Users/linkwind/Code/mangashelf/server/src/main/java/com/mangashelf/external/manga/MangaCatalogSyncHandler.java)：将 `item.latestLabel()` 写入 `remarks` 而非 `tags`；当源支持 `genres()` 时按题材采样抓取入库；`MangaDiscoveryService.discover()` 拉到榜单后顺带异步写入 `manga_catalog_items`，并尽量保留已有有效 `tags`（`ON CONFLICT` 时若新 `tags` 为空则保留旧 `tags`）。

### 1.3 动画与漫画轻量推荐服务（`com.mangashelf.external.RecommendationService` + `RecommendationController`）

- 全部使用显式 Spring JDBC SQL + 确定性打分，无外部依赖，带简短热点缓存与权限边界（访客不读私有表）：
- **`GET /api/v1/recommendations/anime?limit=12&offset=0`**：
  - `catchUp`（登录态）：联合 `favorites`、`catalog_watch_progress`、`external_watch_progress` 找出正在追且 `external_catalog_items.episode_count > last_watched_ordinal` 的番剧，返回 `watchedOrdinal`、`latestOrdinal`、`newEpisodes`、`resumeEpisodeId`。
  - `forYou`：
    - 登录态提取用户最近 90 天收藏（权重 3.0）+ 观看（权重 2.0，近 14 天再乘 1.3）关联到的 `bangumi_subjects.tags` 与 `category_id` 构成兴趣画像；
    - 取候选集（关联 Bangumi 或近期更新的可播条目，最多 300 条，排除已收藏与近期已看到末集的番）；
    - 打分：`0.45 * tagAffinity + 0.30 * bayesianRating + 0.15 * freshness + 0.10 * categoryMatch`，其中 `bayesianRating = ((rating * 10 + 7.2 * 5) / 15) / 10`；
    - 多样性重排：同一主标签最多连续出现 2 部；按 `offset` 循环切片支持「换一批」；附带 `reason`（如 `常看 奇幻·冒险`、`本季高分可播`）。
  - `seasonalPicks`：本季日历中可播（`covered=true`）优先、按贝叶斯评分 + 近 48 小时更新加权排序的精选榜（供 Hero 轮播与本季必看使用）。
- **`GET /api/v1/recommendations/manga?limit=12&offset=0`**：
  - 升级 `MangaDiscoveryService.fuse()`：
    - 站内可读条目得分乘以 `1.25`，跨源共识条目额外增加 `0.008 * (sourceCount - 1)`，代表条目优先可读源；
    - 作者多样性打散：同主作者不连续出现。
  - `forYou`（个性化）：
    - 登录态提取用户收藏 + 外部漫画阅读进度 + 本地阅读记录关联到的 `manga_catalog_items.tags` 偏好；
    - 从 `manga_catalog_items` + 发现流综合榜汇总候选（排除已收藏/在读），按题材亲和度 + 跨源热度 + 站内可读 + 近期同步打分，附带 `reason`（如 `常看 热血·奇幻`、`多源热推 · 站内可读`）并按 `offset` 支持「换一批」。

### 1.4 收藏封面补齐

- [FavoriteRepository.java](file:///Users/linkwind/Code/mangashelf/server/src/main/java/com/mangashelf/library/FavoriteRepository.java)：查询外部漫画收藏时增加 `LEFT JOIN manga_catalog_items mc ON mc.source = f.manga_source AND mc.source_item_id = f.manga_id`，按 `COALESCE(NULLIF(p.cover_url, ''), NULLIF(mc.cover_url, ''))` 返回封面。

## 2. 前端架构与页面设计（`web/`）

1. **顶栏与路由（[App.tsx](file:///Users/linkwind/Code/mangashelf/web/src/App.tsx)）**：
   - 顶栏 5 项：`首页` (`/`)、`番剧` (`/anime`)、`漫画` (`/manga`)、`时刻表` (`/schedule`)、`我的` (`/me`，登录可见；手机端悬浮菜单同步更新)。
   - 旧路由兼容重定向：`/shelf` → `/me?tab=favorites`，`/history` → `/me?tab=history`，`/manga-index` → `/manga?tab=index`，`/manga-ext` → `/manga?tab=search`（保留 `/manga-ext/:mangaId` 与 `/manga-ext/read/:chapterId` 详情和阅读路由不变）。
   - 全量路由级 `React.lazy` + `<Suspense>`，将播放器（`hls.js`）、阅读器、上传页移出首屏主包。
2. **「我的」中心（`web/src/pages/Me.tsx`）**：
   - 三个视角 Tab：`继续追更`（合并追更新集提醒 + 继续观看 + 继续阅读）｜`收藏`（带取消收藏悬浮按钮 + Toast）｜`历史`（按时间展开的阅读/观看记录）。
   - 统一内容类型过滤：`全部` / `动画` / `漫画`。
3. **漫画双 Tab 与全流搜索（重构 `web/src/pages/MangaIndex.tsx`）**：
   - 顶部集成「发现推荐 / 分类索引」双 Tab（URL `?tab=discover` / `?tab=index`）+ 全流搜索框（输入即切换或进入聚合搜索视图，展示各流状态与按流筛选胶囊，同作卡片展示多流徽章与切换入口）。
   - 发现推荐 Tab：Hero 轮播（仅取站内可读高共识条目）→ 继续阅读（登录态）→ 为你推荐（带理由徽章 + 换一批）→ 全网综合热榜 → 精简分区行 → 本地漫画。
   - 分类索引 Tab：来源流筛选（全部 / 单源）+ 题材筛选 + 状态 + 排序 + `<Pagination>` 分页条，全部同步 URL。
4. **番剧索引升级（[AnimeIndex.tsx](file:///Users/linkwind/Code/mangashelf/web/src/pages/AnimeIndex.tsx)）**：
   - 分区 / 题材（动态拉取） / 状态 / 年份 / 排序（最近更新、评分最高、集数最多）多维筛选栏 + `<Pagination>` 真分页 + URL 双向同步。
5. **动画首页升级（[Home.tsx](file:///Users/linkwind/Code/mangashelf/web/src/pages/Home.tsx)）**：
   - 移除重复的 `SeasonSection`；
   - 接入 `/api/v1/recommendations/anime`：精选 Hero 轮播 → 追更提醒 & 继续观看（标出新更集数）→ 为你推荐（带理由标签与换一批）→ 新番时刻表速览 → 最近更新 → 本季必看。
6. **全局搜索升级（[Search.tsx](file:///Users/linkwind/Code/mangashelf/web/src/pages/Search.tsx)）**：
   - 漫画 Tab 改为调用 `/api/v1/manga-discovery/search`（覆盖所有流并去重、显式展示流状态）；
   - 站内 Tab 接入已有的混合检索 `/api/v1/search/documents`（支持 `WORK`/`ANIMATION` 召回，保留对白检索兼容）。
7. **共享组件**：
   - `web/src/components/Pagination.tsx`：通用页码分页条（上一页、省略号页码、下一页、总数显示、切页滚顶）。
   - `web/src/components/EpisodePlayerShell.tsx`：抽离 `BrowseWatch.tsx` 与 `ExternalWatch.tsx` 共用的线路切换、正片/番外分栏、选集网格与上下集导航。
