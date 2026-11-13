## Context

动机见 proposal.md，依据 docs/distributed-evolution.md 4.2、4.4。当前实现事实（2026-11-13 核验）：

- `library_entries(user_id, work_id)` 只支持本地作品收藏；`Shelf.tsx` 把收藏、外部漫画进度、观看历史混排；`History.tsx` 只展示外部漫画与动画进度。
- `watch-history` 与 `continue-watching` 共用 `ExternalRepository.recentProgress`，返回按集展开的行；外部观看进度键为 `(user, work, ordinal)`，浏览态播放无任何进度。
- `BrowseWatch` 与 `ExternalWatch` 各自实现解析轮询、选集、进度写入（约 60 行重复）。
- `materialize` 被 `Schedule`、`CatalogDetail`、`BrowseWatch` 的播放入口调用，创建用户作品并收编公共目录剧集。
- 外部集在物化后保留 `catalog_item_id`（`adoptEpisodes` 只补 `work_id`），可据此设计稳定进度键。

## Goals / Non-Goals

**Goals:**

- 收藏、历史、继续观看三者数据与页面职责分离。
- 收藏覆盖三类目标，数据库约束明确，不做通用资源表。
- 浏览态播放有服务端进度，且导入作品前后续播位置不变。
- 播放默认不创建作品；物化只作为显式导入。

**Non-Goals:**

- 不改阅读/观看进度的版本冲突语义（上一变更已统一）。
- 不改外部漫画阅读器与目录同步。
- 不引入事件表或通用活动日志；历史由现有进度数据派生。
- 不做收藏分组、推荐等扩展功能。

## Decisions

1. **收藏表：单表 + 三类明确目标列 + CHECK + 部分唯一索引。**
   ```sql
   CREATE TABLE favorites (
     id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
     user_id uuid NOT NULL REFERENCES users(id),
     work_id uuid REFERENCES works(id),
     manga_source text, manga_id text, manga_title text,
     catalog_item_id uuid REFERENCES external_catalog_items(id),
     created_at timestamptz NOT NULL DEFAULT now(),
     CHECK ((work_id IS NOT NULL)::int
          + (COALESCE(manga_id <> '', false) AND manga_source <> '')::int
          + (catalog_item_id IS NOT NULL)::int = 1)
   );
   ```
   部分唯一索引分别约束 `(user, work)`、`(user, source, manga_id)`、`(user, catalog_item)`。
   迁移：`library_entries` 数据复制为 work 收藏后删除旧表。
   备选：三张收藏表——列表聚合需要三次查询与内存合并，响应类型也要重复；单表可一次查询完成。

2. **外部漫画收藏只存标题快照，封面从阅读进度回填。**
   收藏不依赖上游接口；封面在列表查询时 LEFT JOIN `external_manga_progress` 取得（用户读过才有）。
   不接受客户端提交的任意封面 URL，避免把收藏变成任意图床引用。
   备选：收藏时调用漫画源详情补封面——引入上游依赖与失败路径，收益低。

3. **进度键统一为"集标识"路由：目录条目优先（可直接或经作品反查）。**
   新增 `catalog_watch_progress(user_id, catalog_item_id, ordinal, episode_id, position_ms,
   duration_ms, version, updated_at)`，主键 `(user, catalog_item_id, ordinal)`。
   新增统一端点 `GET/PUT /api/v1/me/episode-progress`：解析剧集归属时取
   `COALESCE(episode.catalog_item_id, item.id WHERE item.work_id = episode.work_id)`；
   非空写目录进度表，否则写既有 `external_watch_progress`。
   数据库 CHECK 要求 `work_id` 与 `catalog_item_id` 互斥（收编时清空后者），
   因此通过作品反查目录条目来保持导入前后同一进度键，不修改约束。
   备选：允许两列同时非空——需要改 CHECK 与收编语义，影响面更大。

4. **历史与继续观看用同一批进度数据、不同粒度。**
   历史 = 按章节/集展开的最近活动（本地阅读取 `reading_progress`，外部漫画取 `manga_progress`，
   动画取 `watch_progress`，外部观看取两类进度表）；继续观看 = 按作品/目录条目聚合的最新一条
   （本地漫画按 work、外部动画按 work 或 catalog item 分组）。
   备选：新增访问事件表——当前需求不需要独立事件，进度数据已足够且不增加写入路径。

5. **API 收敛到 `library` 模块，退役旧端点。**
   新增 `/me/favorites/...`、`/me/history`、`/me/continue`、`/me/episode-progress`；
   退役 `/me/library`（收藏）、`/me/watch-history`、`/me/continue-watching`、`/external-progress`、
   `/me/external-progress`。前后端同版本发布，不做兼容层。

6. **物化保留为显式导入，播放路径不调用。**
   `POST /catalog-items/{id}/materialize` 保留并改为「导入为我的作品」；`Schedule` 播放跳转
   `/browse/{itemId}`，`CatalogDetail` 主操作为立即播放与收藏，导入是次要操作并说明后果。

7. **播放逻辑抽取为共享 hook，不新建通用框架。**
   `useEpisodePlayback(episodes, currentId, progressTarget)` 封装解析轮询与版本化进度写入；
   两个页面保留各自的数据加载与渲染。备选：合并为一个页面——作品态与浏览态的入口、链接和
   收藏语义不同，合并会引入大量条件分支。

8. **迁移 V24 顺序：建新表 → 复制收藏 → 删旧表。**
   一次性迁移，无数据丢失；回滚可反向重建 `library_entries`（work 收藏仍在 favorites 中）。

## Risks / Trade-offs

- [BREAKING 接口导致旧前端失效] → web 与 api 同一次构建发布；验证覆盖首页、详情、收藏、历史、播放。
- [目录条目未物化时播放，剧集未绑定 work] → 解析任务只依赖 episode 行，与 work 无关；验证浏览态解析与进度。
- [历史/继续查询跨四张表，性能与排序复杂] → 单用户查询、各自 LIMIT 后内存合并按时间排序；后续如需可加物化视图（change 3 的 Redis 缓存可承接）。
- [外部漫画收藏标题依赖客户端传入] → 仅作文本展示，限长并去空白；真实标题仍由详情页显示。
- [导入前后进度稳定依赖目录反查] → 收编清空 `catalog_item_id` 后通过 `work_id` 反查目录条目；
  验证场景覆盖"浏览观看 → 导入 → 续播"。

## Migration Plan

1. Flyway V24（建表、复制收藏、删 `library_entries`、建 `catalog_watch_progress`）。
2. 后端与前端同一构建发布；旧收藏自动出现在新收藏列表。
3. 回滚：代码回滚后 V24 保留（旧代码不再读 favorites，需人工把 work 收藏复制回 `library_entries`，仅回滚时执行）。

## Open Questions

无。历史粒度（按章节/集展开）与继续观看粒度（按作品聚合）已在决策 4 明确。

## 2026-11-13 实施追记

- **进度键稳定改为作品反查**：数据库 CHECK `ext_ep_owner_check` 要求 `work_id` 与
  `catalog_item_id` 互斥，收编必须清空后者（尝试保留两者会违反约束，已实测）。
  `episodeTarget` 改为 `COALESCE(e.catalog_item_id, c.id WHERE c.work_id = e.work_id)`，
  导入前后同一剧集仍解析到同一目录条目进度键。
- **新增缺参处理器**：`MissingServletRequestParameterException` → 400 `INVALID_ARGUMENT`，
  不再落入通用 500（收藏端点缺必填查询参数时暴露）。
- **构建缓存陷阱**：删除迁移文件后 `target/classes` 仍残留旧资源并进入 jar，
  导致 Flyway 执行已删除的 V25 失败；重打包前需删除 `target/classes` 下的残留。
