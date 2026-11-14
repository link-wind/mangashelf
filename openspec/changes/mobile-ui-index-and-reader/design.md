## Context

四个体验缺口都是前端为主。当前事实：

- styles.css 917 行，已有零散 @media 断点（watch-layout/schedule/footer/manga-layout），但导航、搜索框（固定 420px）、首页 hero、详情 hero、阅读器控件均无小屏适配。
- `AnimeIndex` 有 4 个硬编码类型 tab（资源站 category_id），数据侧 `remarks` 含「已完结/更新至第 N 集/HD」，`category_name` 含类型；`resourceUpdatedAt`/`episodeCount` 可作排序。
- `listItems(cursor, limit, q, sourceId, categoryId)` 尚无 status/sort。
- `ExternalMangaReader` 键盘仅 Esc/F；进度与章节列表已加载（detail.chapters）。本地 `Reader.tsx` 通过 `/works/{id}` 可拿章节列表。
- 漫画无统一资源目录（各源为实时 API），索引页只能基于 search/rank/sections 组合。

## Goals / Non-Goals

**Goals:**

- 390px 视口下核心页面无横向滚动、控件可用。
- 番剧索引提供状态/排序服务端筛选。
- 两个阅读器有目录面板与键盘/滚轮导航。
- 漫画索引页对齐番剧索引形态。

**Non-Goals:**

- 不做原生 App/独立移动端站点。
- 不为漫画源新增目录同步（第二阶段前不做后端数据面扩张）。
- 不改播放器（Watch/BrowseWatch）交互，仅响应式布局。
- 不引入 UI 框架，纯手写 CSS。

## Decisions

1. **响应式策略：补断点而非重构布局。** 以 960px/720px/480px 三档补齐导航（wrap + 收纳搜索）、
   hero（单栏）、卡片网格（auto-fill 最小列宽）、阅读器控件（缩尺寸）。验证以 IAB 390×844
   截图为准，发现具体坏点逐个修，不做全局重写。
   备选：移动端独立样式表——维护成本高，断点补充足够。

2. **状态筛选在服务端 SQL 过滤（`status` 参数）。** remarks 是自由文本：`已完结`→completed、
   `更新至`→ongoing、`HD/剧场版`→movie 类；SQL 用明确的 `ILIKE` 组合（显式 SQL 仓库一贯做法）。
   排序 `sort=updated|episodes` 映射 `resourceUpdatedAt DESC` / `episodeCount DESC`。
   备选：前端全量拉取本地过滤——列表大后浪费带宽。

3. **阅读器键位集中到一个 keydown 处理器，按当前模式分发。** 滚动模式 ↑/↓ 用
   `scrollIntoView` 到相邻页锚点（与滚动位置一致性由既有 data-reader-page 锚点保证）；
   双页模式 ←/→ 与 wheel（150ms 节流）走既有 jumpSpread/jumpPage；方向语义复用
   `settings.dir`。两个阅读器各自实现（结构不同），不抽公共 hook——交互逻辑 ~60 行，
   抽象收益低。
   备选：键盘处理 hook 化——两页面状态模型差异大，强行统一会加间接层。

4. **目录面板：阅读器内抽屉，数据复用。** 外部阅读器已有 `detail.chapters`（渲染章节格）；
   本地 Reader 需补一次 `/works/{id}` 章节列表加载。倒序切换为面板内状态。
   备选：独立目录页——阅读中跳走再跳回丢位置，体验差。

5. **漫画索引独立路由 `/manga-index`，不动 `/manga` 首页。** 首页承担发现流（分区横排），
   索引页承担检索式浏览（源 tab + 搜索 + 网格）；导航加「漫画索引」入口。数据面只用
   既有 `/manga-sources/{source}/search|rank|sections`，不新增后端。
   备选：改造 /manga——两个信息架构混在一份布局里会退化。

## Risks / Trade-offs

- [ remarks 解析状态不精确（如「HD」既非连载也非完结）] → 独立 movie/hd 类筛选，不强行归类。
- [ 外部源搜索无分页 ] → 索引页首屏 24 条 + 明确提示（部分源支持深分页则接入），不做假分页。
- [ 滚轮翻页误触 ] → 150ms 节流 + 仅双页模式启用；单页/滚动模式滚轮保持原生滚动。
- [ 响应式坏点枚举不全 ] → IAB 390px 截图逐页核验（首页/索引/详情/阅读/播放/收藏/历史）。

## Migration Plan

纯前端 + 一个只读 API 参数扩展；无数据迁移。回滚即 revert。

## Open Questions

无。
