## Why

四个用户可见的体验缺口：手机端部分 UI 显示不出来（布局为桌面设计、响应式断点不足）；番剧索引只有四个类型 tab，缺少咕咕番式的状态/排序筛选；漫画阅读器没有章节目录、条漫/双页模式缺少键盘与滚轮导航；番剧有索引页而漫画只有首页发现流，缺少同形态的索引页。

## What Changes

- 手机端适配：styles.css 补齐导航/搜索框/网格/详情页/阅读器控件的响应式断点，关键页面在 390px 视口可用。
- 番剧索引分类扩展：类型 tab 之外增加状态筛选（连载中/已完结/剧场版，服务端按 remarks/categoryName 过滤）与排序（最近更新/集数最多），服务端 `listItems` 增加对应参数。
- 阅读器改进（本地 Reader 与外部漫画阅读器）：新增章节目录抽屉（当前章高亮、点击跳转）；条漫/单页滚动模式支持 ↑/↓ 键逐页移动；双页模式支持 ←/→ 键与滚轮翻页（遵循阅读方向设置）。
- 新增漫画索引页（`/manga-index`，参照番剧索引）：源 tab + 搜索 + 封面网格，复用既有 search/rank/sections 能力，不新增后端数据面。

## Capabilities

### New Capabilities

- `web-ui`：站点级 UI 要求——移动端响应式。

### Modified Capabilities

- `external-animations`：番剧索引「番剧目录可检索」要求扩展状态与排序筛选。
- `manga-catalog`：新增阅读器章节目录、阅读器键盘与滚轮导航、漫画索引页三项要求。

## Impact

- 前端为主：`styles.css`（响应式）、`AnimeIndex.tsx`、新 `MangaIndexPage`/路由、`ExternalMangaReader.tsx`、`Reader.tsx`、`App.tsx`（导航）
- 后端：`ExternalController.listItems` 加 status/sort 参数、`ExternalCatalogRepository` 对应查询
- 无数据库迁移；无新依赖
