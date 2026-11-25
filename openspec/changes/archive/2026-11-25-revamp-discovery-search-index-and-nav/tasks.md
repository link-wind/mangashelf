# Tasks: 导航精简、全流漫画搜索、分类分页索引与双端推荐优化

## 1. 后端：漫画源熔断、全流搜索与目录清洗分页
- [x] 1.1 在 `MangaDiscoveryService` 引入单源健康状态与 5 分钟自动熔断冷却，升级 `fuse()` 打分（站内可读加权、跨源共识加成、作者多样性打散），并将发现流结果异步沉淀入 `manga_catalog_items`
- [x] 1.2 实现跨流漫画聚合搜索 `GET /api/v1/manga-discovery/search`（并行扇出在线搜索源 + `manga_catalog_items` + 站内有权限 `works`，共享 5s 超时隔离、跨流聚簇去重、多流列表与 `sourceStatus`）
- [x] 1.3 升级 `MangaCatalogRepository` / `MangaCatalogController`：`source` 可选支持全流查询，支持 `genre`、`status`、`sort`、1-based `page` 与 `totalPages`；修复 `MangaCatalogSyncHandler` 标签错写并保留已有有效 `tags`

## 2. 后端：番剧多维分类分页与双端推荐接口
- [x] 2.1 升级 `ExternalCatalogRepository` / `ExternalCatalogService` / `ExternalController`：修复 `count()` 漏传 `q`，统一支持 `page` + `totalPages`、`year` 筛选、`sort=rating` 排序，并新增 `GET /api/v1/catalog-items/genres`
- [x] 2.2 新增 `RecommendationService` 与 `RecommendationController`：实现 `GET /api/v1/recommendations/anime`（追更提醒、画像+贝叶斯+多样性为你推荐、本季精选）与 `GET /api/v1/recommendations/manga`（个性重排+理由+换一批）
- [x] 2.3 补齐 `FavoriteRepository` 外部漫画收藏封面关联 `manga_catalog_items` 兜底
- [x] 2.4 补充后端单元测试与真实 PostgreSQL 集成测试覆盖全流搜索聚簇/熔断、分类分页计数与推荐打分逻辑

## 3. 前端：导航收口、「我的」、双 Tab 漫画、分类分页与懒加载
- [x] 3.1 新增通用分页组件 `Pagination.tsx` 与共享播放壳 `EpisodePlayerShell.tsx`（重构 `BrowseWatch.tsx` 与 `ExternalWatch.tsx`）
- [x] 3.2 新增「我的」页面 `Me.tsx`（继续追更 / 收藏 / 历史三 Tab + 全部/动画/漫画筛选）
- [x] 3.3 升级 `AnimeIndex.tsx`（分区/动态题材/状态/年份/排序 + `<Pagination>` + URL 同步）
- [x] 3.4 重构 `MangaIndex.tsx` 为双 Tab（发现推荐 + 分类分页索引）并集成全流漫画搜索、多流切换、继续阅读与为你推荐
- [x] 3.5 升级 `Home.tsx`（接入动画推荐接口、追更新集提醒、为你推荐与换一批、移除重复 `SeasonSection`）与 `Search.tsx`（接入全流漫画搜索）
- [x] 3.6 重构 `App.tsx`：顶栏收口为 5 项（首页/番剧/漫画/时刻表/我的）、更新手机端悬浮菜单、旧路由兼容重定向、路由级 `React.lazy` 懒加载拆包

## 4. 构建、真实环境验证与归档
- [x] 4.1 运行全量后端测试（69/69 通过）与前端生产构建，记录拆包后主包体积变化（1,012 KB → 279.67 KB）
- [x] 4.2 重建并启动 Docker API/Worker 容器，实测全流漫画搜索、分类分页、双端推荐与「我的」页面端到端链路，写入 `docs/validation/revamp-discovery-search-index-and-nav.md`
- [x] 4.3 同步规格并归档 OpenSpec 变更
