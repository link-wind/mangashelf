# Proposal: P2 能力深化（Bangumi 元数据补齐、已看完/已读完状态、番剧详情页重构与追番日历 .ics 订阅）

## Why

完成 P0+P1 的视觉、时刻表、索引与播放升级后，仍有 4 个直接影响精准度与完整闭环的数据/交互短板：
1. Bangumi 公开日历接口不含 `tags`、完整 `air_date` 与详细 `summary`，导致番剧索引的「动态题材」与「年份筛选」数据稀疏、个性化与相似推荐粒度不足。
2. 收藏仅有「已收藏 / 未收藏」二态，无法区分「在追」与「已看完/已读完」，导致看完的连载番仍反复触发追更提醒，也无法给高完成度作品更高推荐权重。
3. 番剧目录详情页（`CatalogDetail.tsx`）目前过于单薄，缺少面包屑、题材标签跳转、数据 KPI 卡、分集直选与相似推荐。
4. 缺少面向个人日历客户端的轻量追番订阅方式。

## What Changes

1. **Bangumi v0 条目详情元数据补齐**：在 `BangumiService` 新增礼貌限速的 `enrichSubjectsMetadata()`（补全 `tags`/`air_date`/`summary`/`rating`），接入 `BangumiSyncHandler` 并提供管理员按需触发端点 `POST /api/v1/admin/bangumi-enrich`。
2. **「已看完 / 已读完」状态（V30）**：在 `favorites` 表新增 `status`（`WATCHING` | `COMPLETED`，数据库 `CHECK` 约束）与 `completed_at`；新增状态切换接口并联动推荐引擎（`COMPLETED` 提高题材权重、移出追更提醒）；在「我的」、番剧详情页与漫画详情页提供一键标记与状态筛选。
3. **番剧详情页重构（`CatalogDetail.tsx`）**：扩展 `GET /api/v1/catalog-items/{id}` 返回 `tags`/`airDate`/`bangumiName`；前端增加面包屑、高清头图评分卡、可点击题材胶囊、4 项 KPI 条、吸顶分区锚点、内嵌线路选集网格与相似推荐。
4. **追番日历 `.ics` 订阅**：新增 `GET /api/v1/calendar/ics`（标准 RFC 5545 周循环 `VEVENT`，支持全本季或登录态 `onlyMine=true`），在 `/schedule` 侧栏提供一键下载与复制订阅链接。
