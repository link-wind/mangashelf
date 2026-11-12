## 1. Python canvas 截图链路退役

- [x] 1.1 删除 `processing/tasks/manga_resolve.py` 的 `_resolve_chapter_canvas` 与 `chapter-canvas` 模式入口（含仅为该模式存在的站内 URL 限制逻辑）
- [x] 1.2 删除 `processing/tests/test_bilibili_capture.py` 中 canvas 用例，保留 HTTP 模式测试并跑通 processing 测试套件
- [x] 1.3 本地启动冒烟：确认哔哩 HTTP 阅读不受影响，两个 410 桩（`/api/v1/external-manga/captured/...`、`/api/v1/manga-sources/bilibili/capture/...`）响应不变

## 2. 历史任务处置与 Java 旧实现退役

- [x] 2.1 统计 `jobs` 表历史 `KIND_PROCESS` 待处理任务数量与状态，按 design 决策 1 取消并追加「单图派生链路已退役」事件（一次性 SQL，统计结果记录到验证文档）
- [x] 2.2 删除 `DeriveJobHandler` 及其在 `JobExecutor` 的 kind 注册，确认无残留引用，后端编译通过
- [x] 2.3 删除 `MangaMatcher.findReadable()` 及仅为它服务的字段/依赖/缓存，保留 `similarity()`；综合榜聚合请求回归通过

## 3. 后端统一路由补齐

- [x] 3.1 核对 `MangaSourceController` 对 `mangadex` 的 search/detail/chapter pages 覆盖，补齐缺失路由（复用 `MangaDexSource` 统一实现，不新增通用路由层）
- [x] 3.2 真实环境验证 MangaDex 经 `manga-sources` 的搜索、详情、章节页图与外链章节提示

## 4. 前端统一切换与旧版书库移除

- [x] 4.1 `externalManga.ts`、`ExternalMangaDetail.tsx`、`ExternalMangaReader.tsx` 统一改走 `/api/v1/manga-sources/{source}`，删除 `source === 'mangadex'` 分支
- [x] 4.2 浏览器验证 MangaDex 与规则源的详情、阅读、进度保存路径正常（进度接口与数据标识不变）
- [x] 4.3 移除 `App.tsx` 的 `/library` 路由与导航链接（顶部与页脚），删除 `Library.tsx`，全仓确认无残留引用

## 5. 旧端点退役

- [x] 5.1 确认 `/api/v1/external-manga/` 的 search、detail、pages 无调用方后删除；image 代理与 captured 410 桩保留
- [x] 5.2 全局检索前端与文档中的旧端点引用并清理

## 6. 验证与收尾

- [x] 6.1 按 specs 验收场景逐条验证：MangaDex 统一接口、旧链接兼容（无 source 参数）、历史截图链接 410 提示、外链章节提示
- [x] 6.2 验证场景、结果与已知限制写入 `docs/validation/retire-legacy-implementations.md`
- [x] 6.3 `openspec validate` 通过后准备归档
