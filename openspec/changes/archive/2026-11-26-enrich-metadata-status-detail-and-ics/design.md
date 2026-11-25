# Design: P2 元数据补齐、已看完状态、详情页重构与 .ics 订阅

## 1. 数据库变更（`V30__favorite_status.sql`）

- 为 `favorites` 新增列：
  - `status varchar(16) NOT NULL DEFAULT 'WATCHING' CHECK (status IN ('WATCHING', 'COMPLETED'))`
  - `completed_at timestamptz`
- 约束直接下沉数据库；既有数据自动归为 `WATCHING`，零破坏性兼容。

## 2. 后端设计

- **Bangumi 元数据补齐（`BangumiService` / `BangumiSyncHandler` / `ExternalController`）**：
  - `enrichSubjectsMetadata(int limit)` 查询 `bangumi_subjects` 中缺失 `tags` 或 `air_date` 或 `summary` 的条目（优先 `in_calendar = true`），调用 `GET https://api.bgm.tv/v0/subjects/{id}` 提取 `tags[].name`、`meta_tags`、`date`、`summary`、`rating.score`、`name_cn`，200ms 礼貌间隔并按单条异常隔离，写完刷新 `calendar` 与 `catalog_genres` 热点缓存。
  - `POST /api/v1/admin/bangumi-enrich?limit=80` 供管理员立即触发补齐并返回统计 `{ checked, enriched, failed }`。
- **收藏状态（`FavoriteRepository` / `FavoritesService` / `FavoritesController`）**：
  - `FavoriteRow` 新增 `status` 字段；
  - 新增 `PUT /api/v1/me/favorites/catalog/{itemId}/status`、`PUT /api/v1/me/favorites/work/{workId}/status`、`PUT /api/v1/me/favorites/manga/status`（支持未收藏时直接写入并置状态，已收藏时幂等切换 `WATCHING`/`COMPLETED`）。
- **推荐联动（`RecommendationService`）**：
  - `findAnimeCatchUp` 过滤用户已标记 `COMPLETED` 的目录条目；
  - `loadAnimeUserProfile` 与 `loadMangaUserProfile` 对 `COMPLETED` 条目赋予更高偏好权重（`3.6` vs `3.0`）。
- **详情扩展与 `.ics` 导出（`ExternalCatalogService` / `ExternalController` / `SecurityConfig`）**：
  - `CatalogDetail` 扩展 `bangumiName`、`airDate`、`tags`；
  - `GET /api/v1/calendar/ics?onlyMine=false` 生成 RFC 5545 标准 `VCALENDAR`（`RRULE:FREQ=WEEKLY;BYDAY=MO..SU`），支持访客拉公开本季或认证用户/Bearer Token 拉 `onlyMine=true`。

## 3. 前端设计

- **`CatalogDetail.tsx`**：面包屑 + 高清双栏头图 + 题材胶囊（直达 `/anime?genre=`）+ 4 KPI 卡 + `立即播放 / 收藏 / 标记已看完 / 导入作品` + 吸顶锚点条 + 内嵌多线路分集选集网格（超 30 集自动分段）+ 相似推荐。
- **`ExternalMangaDetail.tsx` & `Me.tsx`**：增加「标记已读完 / 已看完」按钮与「全部 / 在追 / 已看完」状态筛选胶囊及卡片徽章。
- **`Schedule.tsx`**：侧栏新增「追番日历订阅 (.ics)」卡片，支持下载 `.ics` 文件与复制订阅地址。
