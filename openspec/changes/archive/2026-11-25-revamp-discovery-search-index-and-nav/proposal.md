# Proposal: 导航精简（「我的」+ 漫画双 Tab）、全流漫画搜索、分类分页索引与双端推荐优化

## Why

当前前端与发现/检索/索引链路存在四类核心体验问题：
1. **导航冗余且心智割裂**：顶栏有 7 个入口（「漫画」「漫画索引」「外部漫画搜索」三者职责重叠；「我的收藏」「观看历史」分离；原建议的「书架」命名仅覆盖漫画不覆盖动画）。
2. **漫画搜索局限于单源**：漫画索引页与外部搜索页一次只能搜单个源，全局搜索仅查库内少量缓存条目，无法同时覆盖所有在线流（腾讯、包子、拷贝等）、目录库流（哔哩哔哩等）与本地书库，且故障源超时会拖慢响应。
3. **索引缺乏完整分类与页码分页**：番剧索引仅 `sort=episodes` 支持偏移分页、其余仅能游标「加载更多」，且缺少评分排序与年份筛选、`count()` 漏传关键词 `q`；漫画索引强制单源、无页码分页，且目录入库把 `latestLabel` 误写入 `tags` 导致题材筛选噪声大。
4. **动画与漫画推荐策略单一**：首页仅按裸评分和日历平铺且与 `SeasonSection` 重复，缺乏基于用户观看/收藏的个性化与新集追更提醒；漫画发现流仅做等权 RRF，不区分源健康状态、站内可读性与阅读偏好，也没有作者多样性打散。

此外，前端打包为单一 1 MB bundle（未按路由懒加载），且 `BrowseWatch` 与 `ExternalWatch` 存在大段重复播放壳代码。

## What Changes

1. **导航与「我的」中心（`web-ui`）**：
   - 顶栏收口为 5 项：`首页`、`番剧`、`漫画`（内含「发现推荐 / 分类索引」双 Tab）、`时刻表`、`我的`。
   - 新增 `/me`（「我的」页面）：整合 **继续追更**、**收藏**、**历史** 三个 Tab，均支持 `全部 / 动画 / 漫画` 类型筛选；旧路由 `/shelf`、`/history`、`/manga-index`、`/manga-ext` 保留兼容重定向。
   - 补齐外部漫画收藏在未阅读时的封面兜底（`FavoriteRepository` 关联 `manga_catalog_items`）。
   - 路由级 `React.lazy` 懒加载拆包，抽离共享 `EpisodePlayerShell` 消除播放壳重复，移除首页重复 `SeasonSection`。

2. **漫画全流聚合搜索与源健康熔断（`manga-sources`）**：
   - 新增 `GET /api/v1/manga-discovery/search`：并发扇出所有可用中文在线搜索流 + `manga_catalog_items` 目录库流 + 站内 `works` 本地流。
   - 单源硬超时隔离（5s）+ 连续失败自动熔断冷却（5 分钟），返回各流状态 `sourceStatus`。
   - 基于 `MangaMatcher` 做跨流同作聚簇去重，优先站内可读代表源并保留多源切换列表；实时命中条目异步回写目录库。
   - 漫画页顶部搜索框与全局 `/search` 统一接入全流搜索，支持按流筛选。

3. **番剧与漫画多维分类 + 页码分页索引（`external-animations` / `manga-catalog` / `web-ui`）**：
   - `GET /api/v1/catalog-items` 全面支持 1-based `page` + `limit` + `total` + `totalPages`，新增 `year` 筛选与 `sort=rating` 排序，新增 `GET /api/v1/catalog-items/genres` 返回库内高频题材，并修复 `count()` 漏传 `q` 的问题（保留旧 `cursor` 兼容）。
   - `GET /api/v1/manga-catalog` 与 `/genres` 将 `source` 改为可选（缺省查全流），新增 `sort`（`updated` / `episodes`）与 1-based `page` + `totalPages`；修复 `MangaCatalogSyncHandler` 标签写入，将 `latestLabel` 写入 `remarks`，并补充按源题材/发现流异步沉淀入库。
   - 前端提供统一 `<Pagination>` 组件与 URL 参数双向同步的多维筛选栏。

4. **动画与漫画推荐机制优化（`external-animations` / `manga-sources`）**：
   - 新增 `GET /api/v1/recommendations/anime`：返回「追更提醒（检测已看/收藏连载番的新更集数）」、「为你推荐（收藏/观看题材与分区画像 + 贝叶斯评分 + 活跃度 + 同类打散 + 可解释理由 + 换一批偏移）」与「本季精选（可播优先 + 贝叶斯评分 + 近期更新）」。
   - 升级 `MangaDiscoveryService` 与新增 `GET /api/v1/recommendations/manga`：引入源健康熔断、站内可读 `1.25x` 加权、跨源共识加成、同作者多样性打散，以及基于用户收藏/阅读历史的「为你推荐」与漫画页「继续阅读」。

## Impact

- 不新增外部中间件或 ML 框架，继续遵守 Spring JDBC 显式 SQL 与精简模块原则。
- 所有新增参数与字段向后兼容既有接口与旧路由书签。
