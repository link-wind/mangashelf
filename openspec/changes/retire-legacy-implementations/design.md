## Context

动机见 proposal.md。本变更是 docs/distributed-evolution.md 第 4.1/4.2 节的落地（其设计依据为 DESIGN.md 第 6、11、15、20 节），只记录退役与接口统一的增量决策。

当前代码事实（2026-11-12 核验，基线 `cb5b627`）：

- Java 已无 `chapter-canvas` 派发方，两个 controller 的截图入口返回 410；Python `manga_resolve.py` 仍保留 canvas 模式与测试。
- `MangaMatcher.findReadable()` 无调用方；`similarity()` 被 `MangaDiscoveryService` 综合榜使用。
- `UploadService` 只创建 `KIND_VERIFY`，verify 不再链式派生；`DeriveJobHandler`（`KIND_PROCESS`）无新任务来源。
- `/library` 页面只读 `/api/v1/works` 列表；创建/发布管理在「我的素材」（同样读 `/api/v1/works`），可读列表在漫画专区「我的本地漫画」（读 `/api/v1/manga`）。
- `MangaDexSource` 已实现统一数据源接口；`MangaSourceController` 已按源路由，但 MangaDex 的详情/页图前端仍走 `ExternalMangaController`。

## Goals / Non-Goals

**Goals:**

- 删除无调用方的旧实现及其专属测试，缩小后续多实例改造的涉及面。
- 外部漫画前后端单一协议：`manga-sources/{source}` 覆盖全部源。
- 保留仍被使用的能力：MangaDex 图片代理、综合榜 `similarity()`、410 桩。

**Non-Goals:**

- 不删除 410 桩（留给部署验证后的清理窗口，distributed-evolution 4.1）。
- 不改收藏/历史/进度语义与 materialize（变更序列 2）。
- 不动 `chapter.parse`、上传、分页、缩略图链路。
- 不引入新的通用路由层或插件机制，按源分发沿用现有 `MangaSourceController` 结构。

## Decisions

1. **历史 `KIND_PROCESS` 任务处置：取消并留事件，不迁移执行。**
   verify 自 M1 起不再派生，现存 `KIND_PROCESS` 待处理任务均为旧残留；退役时条件更新为
   CANCELLED 并追加事件说明「单图派生链路已退役」，不自动重跑（显式报错原则，AGENTS.md）。
   备选：让旧 handler 跑完存量再删——会增加一次性兼容代码，收益低。

2. **接口统一的实施顺序：先补齐后端统一路由，再切前端，最后删旧端点。**
   - 确认/补齐 `MangaSourceController` 对 `mangadex` 的 search/detail/chapter pages 能力
     （MangaDex 已有统一源实现，预期只差路由覆盖）。
   - 前端 `externalManga.ts`、`ExternalMangaDetail.tsx`、`ExternalMangaReader.tsx`
     改为统一按源调用，删除 `source === 'mangadex'` 分支。
   - 旧 `/api/v1/external-manga/search|detail|pages` 确认无调用方后删除；
     `/api/v1/external-manga/image` 代理与 captured 410 桩保留。
   备选：新旧端点长期并存——维持双协议维护成本，与收敛目标相悖。

3. **`/library` 直接移除，不迁移页面。**
   三处现有入口已覆盖其全部能力（我的素材＝管理流、我的本地漫画＝可读列表、我的收藏＝收藏）。
   备选：保留为重定向——多一个无意义的路由壳。

4. **Python canvas 模式整体删除而非置灰。**
   Java 已无派发方，保留代码只会误导后续 Worker 改造（浏览器解析移入 Worker 时不需要
   截图模式）；`_resolve_chapter_canvas` 与 `test_bilibili_capture.py` 中 canvas 用例一并删除，
   HTTP 模式测试保留。

5. **`MangaMatcher` 只删 `findReadable()` 及其专属依赖。**
   逐项核对 MangaMatcher 内仅为 findReadable 服务的字段/缓存后删除；`similarity()` 及其
   使用方 `MangaDiscoveryService` 不动。备选：连 similarity 一起搬走重构——超出本变更范围。

## Risks / Trade-offs

- [MangaDex 统一路由存在缺口，切换后阅读不可用] → 顺序决策 2 保证前端切换前先验证
  manga-sources 的 MangaDex 详情/页图；验收场景明确覆盖 MangaDex 章节阅读与外链章节提示。
- [旧外部漫画 URL 失效] → 缺省源按 MangaDex 处理的兼容行为保留（spec「旧链接兼容」）；
  进度数据的 source/manga_id 标识不变，`manga-progress` 不受影响。
- [历史任务处置遗漏导致僵尸 QUEUED] → tasks 中含部署前核查步骤（SQL 统计 + 取消），
  属一次性数据操作，不写入 schema。
- [误删 similarity 影响综合榜] → 删除范围仅限 findReadable 及专属依赖；tasks 中含
  综合榜回归验证。

## Migration Plan

单机 compose 一次性部署，无 schema 变更。提交顺序（每步一个提交）：
Python canvas 删除 → 历史 KIND_PROCESS 处置说明 + Java handler/桥接删除 → 前端统一接口切换
→ 旧端点删除。回滚策略：按提交反向 revert 即可，无数据不可逆操作（取消任务可人工重置）。

## Open Questions

无。历史任务处置与 410 保留时机均已在 Decisions / Non-Goals 中显式确定。

## 2026-11-13 实施追记

- **image_derive.py 一并退役**：`processing/tasks/image_derive.py` 的唯一调用方是 `DeriveJobHandler`，属同一条单图派生链路的 Python 半边，随 handler 一并删除（含 `processing/run.py` 注册项）。无测试引用。
- **新增 404 处理器（范围增量）**：删除旧端点后，未匹配路由被 `GlobalExceptionHandler` 的通用 `Exception` 处理器吞成 500 `INTERNAL_ERROR`，语义错误（实为资源不存在）。新增窄的 `NoResourceFoundException` → 404 `NOT_FOUND` 处理器，影响所有未知路径，符合"显式报错"约定。
- **任务 2.1 实测**：`jobs` 表无任何 `KIND_PROCESS` 任务，取消处置 SQL 未触发。
