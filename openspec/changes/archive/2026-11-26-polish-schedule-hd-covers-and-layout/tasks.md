# Tasks: 高清封面、统一 3:4 栅格、新版时刻表与 P1 交互升级

## 1. 后端：高清封面默认档位与相似番剧推荐接口
- [x] 1.1 更新 `BangumiService` 封面默认档位与预热任务为 `large`，同步更新 `FavoriteRepository`、`PersonalHistoryRepository`、`RecommendationService` 生成 `?size=large`
- [x] 1.2 在 `RecommendationService` 与 `RecommendationController` 新增 `GET /api/v1/recommendations/anime/{catalogItemId}/similar` 及对应单元测试

## 2. 前端 P0：高清封面、统一 3:4 栅格与新版时刻表
- [x] 2.1 升级 `externalManga.ts` 漫画列表高清归一与全站 Bangumi `?size=large` 封面引用
- [x] 2.2 更新 `layout.css` / `components.css` / `pages.css`：1344px 容器、7→5→4→2 响应式 3:4 栅格、182px 横滚对齐、四角角标与 `center 20%` 防裁头顶
- [x] 2.3 重构 `Schedule.tsx`（3 KPI 卡、筛选胶囊、7 列今日高亮周历条、8:4 双栏焦点/紧凑横卡、右侧本周重磅雷达与我的追更表）

## 3. 前端 P1：首页 70/30、索引双视图与跳页、播放选集与章节分段
- [x] 3.1 升级 `Home.tsx`：70/30 左推荐网格 + 右多 Tab 榜单卡、完结补番精选横排与带计数热门题材探索
- [x] 3.2 升级 `Pagination.tsx`（页码直达跳转）、`AnimeIndex.tsx` 与 `MangaIndex.tsx`（网格/双栏列表双视图切换、多题材折叠展开）、`App.tsx`（搜索框一键清空）
- [x] 3.3 升级 `EpisodePlayerShell.tsx` / `BrowseWatch.tsx`（正倒序、长篇集数分段、自动连播开关、侧栏相似推荐）与 `ExternalMangaDetail.tsx` / `MangaDetail.tsx`（超 50 话章节分组）

## 4. 构建、实测与归档
- [x] 4.1 运行后端测试与前端生产构建
- [x] 4.2 重建重启容器，实测相似推荐、高清 `large` 封面与前端页面，写入验证文档并归档提交
