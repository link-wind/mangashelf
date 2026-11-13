## Why

docs/distributed-evolution.md（第 10 节变更序列之 1）判定：四组旧实现已无业务路径但仍在维护——哔哩截图 Python 管线（Java 入口已 410）、无任何调用方的自动跨源桥接 `MangaMatcher.findReadable()`、不再被触发的单图派生任务 `DeriveJobHandler`、与「我的素材」和漫画专区「我的本地漫画」完全重复的旧版书库 `/library` 入口。同时外部漫画维护着 `/api/v1/external-manga/...` 与 `/api/v1/manga-sources/{source}/...` 两套协议，前端对 MangaDex 单独分支。多实例改造（变更序列 3–5）开始前先退役这批代码，避免在即将删除的实现上叠加租约与缓存改造。

## What Changes

- 删除 Python canvas 截图模式：`processing/tasks/manga_resolve.py` 的 `chapter-canvas` 模式与 `processing/tests/test_bilibili_capture.py` 中对应测试。**BREAKING**（Python 任务协议：`chapter-canvas` 不再是受支持模式；Java 侧已无派发方）
- Java 侧哔哩截图 410 响应（`ExternalMangaController`、`MangaSourceController`）短期保留，处理历史旧链接
- 删除 `MangaMatcher.findReadable()` 及其残留的客户端依赖与缓存；保留综合榜仍在使用的 `similarity()`
- 退役 `DeriveJobHandler`（`KIND_PROCESS` 单图派生处理）；退役前核对数据库中历史 `KIND_PROCESS` 待处理任务并处置
- 移除旧版书库 `/library` 导航入口、路由与 `Library.tsx` 页面。已核实无独有管理操作：作品创建/发布/动画管理在「我的素材」，可读本地作品列表在漫画专区「我的本地漫画」，收藏在「我的收藏」
- 外部漫画详情、章节、页图统一走 `/api/v1/manga-sources/{source}/...`，前端消除 MangaDex 单独分支（`ExternalMangaDetail.tsx`、`ExternalMangaReader.tsx`、`externalManga.ts`）
- 退役 `/api/v1/external-manga/` 下 search、detail、pages 旧端点；`/api/v1/external-manga/image` 图片代理独立保留（MangaDex 白名单代理仍在使用）

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `external-manga`：搜索与阅读不再经由本能力的聚合端点，统一改走 `manga-sources` 协议；聚合端点退役后图片代理成为该能力唯一保留端点；阅读器行为（方向、页模式、分区、长条漫）不变
- `manga-sources`：「外部漫画按源路由」扩展为所有源（含 MangaDex）统一经 `manga-sources` 提供详情/章节/页图；哔哩阅读路径明确为 HTTP 页图，截图管线彻底移除

## Impact

- Java：`ExternalMangaController`（删 search/detail/pages，保留 image 代理与 410 桩）、`MangaMatcher`、`ingestion/DeriveJobHandler` 及任务 kind 注册处
- Python：`processing/tasks/manga_resolve.py`、`processing/tests/test_bilibili_capture.py`
- Web：`App.tsx`（导航与路由）、`ExternalMangaDetail.tsx`、`ExternalMangaReader.tsx`、`externalManga.ts`，删除 `Library.tsx`
- 数据：`jobs` 表历史 `KIND_PROCESS` 待处理任务需先核对再退役 handler，无 schema 变更、无新依赖
- 风险边界：删除派生链路不影响漫画上传、分页与缩略图（`chapter.parse` 独立）；删除桥接不影响综合榜聚合（走 `similarity()`）
