# Design: 高清封面、统一 3:4 栅格、新版时刻表与 P1 交互升级

## 1. 后端改动（零表结构变更）

1. **高清封面档位（`BangumiService` / `FavoriteRepository` / `PersonalHistoryRepository` / `RecommendationService`）**：
   - `BangumiService.normalizeCoverSize` 默认档位改为 `large`（保留 `common`/`medium`/`small` 参数兼容）；
   - `BangumiService.warmCalendarCoversAsync` 改为仅预热 `large` 档（一次拉取落盘，复用 `CoverCacheService` + `ETag/304` + 7 天强缓存）；
   - 收藏、历史、追更与推荐返回的 Bangumi 封面路径统一生成 `?size=large`。
2. **播放页相似推荐接口（`RecommendationService` / `RecommendationController`）**：
   - 新增 `GET /api/v1/recommendations/anime/{catalogItemId}/similar?limit=6`（公开只读，已被 `SecurityConfig` 的 `/api/v1/recommendations/**` 放行）；
   - 读取目标条目的 `category_id` 与 `bangumi_subjects.tags`，按共享标签数 + 同分区 + 贝叶斯评分 + 更新活跃度召回 Top N 相似可播番剧（排除自身），返回轻量 `AnimeRecItem` 列表。

## 2. 前端 P0 + P1 改造

1. **高清封面与构图**：全站 Bangumi 封面改用 `?size=large`；`externalManga.ts` 中 `normalizeCoverUrl` 将腾讯 `/\d+$/` → `/0`、哔哩 `@\d+w` → `@600w.webp`；封面图 `object-position: center 20%`。
2. **统一 3:4 栅格体系与四角角标**：`.home-page`/`.watch-page`/`.page` 统一 `max-width: 1344px`；`.card-grid` 统一 `7→5→4→2` 列；`.hscroll`/`.manga-row` 统一 `182px` 宽；统一单行截断标题 + 四角角标（`.badge-tl`/`.badge-tr`/`.badge-bl`/`.badge-br`/`.badge-score`）+ `tabular-nums`。
3. **新版 `/schedule`**：顶部 3 KPI 卡 + 筛选胶囊（全部/可播/我的追番）+ 7 列周历条（自动高亮今日、URL 同步 `?day=`）+ 8:4 双栏（左：焦点 `144×192` 横卡 + 紧凑 `96×128` 双列横卡；右：本周重磅雷达 Top 5 + 我的本周追更表）。
4. **首页 70/30 + 完结行 + 题材探索**：左侧 4×2 推荐网格 + 右侧 3 Tab 榜单卡（本季高分 / 最近热更 / 集数最多）；新增「完结补番精选（一口气看完）」横排与带计数热门题材胶囊。
5. **索引效率**：`Pagination.tsx` 增加页码直达输入；`AnimeIndex.tsx` 与 `MangaIndex.tsx` 支持「网格 / 双栏列表」切换与多题材折叠展开；搜索框加一键清除 `×`。
6. **播放壳与漫画章节**：`EpisodePlayerShell.tsx` 增加正倒序（`↑↓`）、超过 30 集自动区间分组（每 24 集一组）、`连播` 开关（持久化 `localStorage`）与侧栏「相似推荐」3 列小图卡；`ExternalMangaDetail.tsx` 与 `MangaDetail.tsx` 章节超过 50 话自动区间分组。
