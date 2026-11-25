# Tasks: P2 元数据补齐、已看完状态、详情页重构与 .ics 订阅

## 1. 数据库与后端能力
- [x] 1.1 新增 Flyway 迁移 `V30__favorite_status.sql`，更新 `FavoriteRepository` / `FavoritesService` / `FavoritesController` 支持 `WATCHING` / `COMPLETED` 状态与接口
- [x] 1.2 在 `BangumiService` 新增 `enrichSubjectsMetadata()` 与 `buildIcs()`，接入 `BangumiSyncHandler` 并在 `ExternalController` 暴露 `POST /api/v1/admin/bangumi-enrich` 与 `GET /api/v1/calendar/ics`
- [x] 1.3 扩展 `ExternalCatalogService.itemDetail()` 返回 `bangumiName`、`airDate`、`tags`；联动 `RecommendationService` 提高 `COMPLETED` 偏好权重并移出追更提醒
- [x] 1.4 补充单元测试覆盖 `.ics` 生成与 Bangumi v0 详情解析

## 2. 前端 P2 界面升级
- [x] 2.1 重构 `CatalogDetail.tsx`（面包屑、高清头图、题材标签、4 KPI、标记已看完、吸顶锚点、内嵌线路选集网格、相似推荐）
- [x] 2.2 更新 `ExternalMangaDetail.tsx`、`Me.tsx` 支持「已看完 / 已读完」一键标记与状态筛选，在 `Settings.tsx` 增加「补齐题材与年份」按钮
- [x] 2.3 更新 `Schedule.tsx` 侧栏新增「追番日历订阅 (.ics)」下载与复制链接卡片

## 3. 构建、实测与归档
- [x] 3.1 运行后端测试与前端生产构建
- [x] 3.2 应用 `V30` 迁移、重启容器并实测 `.ics` 导出、详情扩展字段与权限边界，写入验证文档并归档提交
