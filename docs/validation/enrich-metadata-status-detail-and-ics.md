# 验证报告：P2 元数据补齐、已看完/已读完状态、番剧详情页重构与追番日历 .ics 订阅

日期：2026-11-26  
关联变更：`openspec/changes/archive/2026-11-26-enrich-metadata-status-detail-and-ics/`

## 1. 数据库迁移、构建与自动化测试

- **Flyway 迁移 `V30__favorite_status.sql`**：经 `migrate` 服务实测应用于 PostgreSQL 18（`Successfully applied 1 migration to schema "public", now at version v30`），`favorites` 表新增 `status`（`CHECK (status IN ('WATCHING', 'COMPLETED'))`）、`completed_at` 与 `idx_favorites_user_status` 索引。
- **后端单元测试**（`maven:3-eclipse-temurin-25`）：
  - `BangumiEnrichAndIcsTest`（v0 详情 `tags`/`date`/`summary`/`rating` 解析 + RFC 5545 周循环 `.ics` 生成）、`RecommendationServiceTest`、`BangumiTitleMatchTest`、`BangumiCoverCacheTest`、`MangaDiscoveryServiceTest` 共 **23/23 通过**（`5.2s`）。
- **前端构建**（`npm --prefix web run build`）：
  - `tsc -b && vite build` 零错误通过，重构后的 `CatalogDetail.js` 独立 chunk 为 `9.14 KB`、`Me.js` `11.09 KB`、`Schedule.js` `12.81 KB`。

## 2. 真实环境端到端实测（`http://localhost:8080` + `http://localhost:5173`）

| 验证项 | 请求 / 场景 | 实测结果 |
|---|---|---|
| 追番日历 `.ics` 公开导出 | `GET /api/v1/calendar/ics?coveredOnly=true` | `HTTP 200`，`Content-Type: text/calendar;charset=utf-8`，生成 `67` 条标准 RFC 5545 `VEVENT`（含 `RRULE:FREQ=WEEKLY;BYDAY=...`） |
| 个人追番 `.ics` 权限边界 | 访客请求 `GET /api/v1/calendar/ics?onlyMine=true` | 正确返回 `HTTP 401`（不泄露任何私有收藏） |
| 番剧详情扩展字段 | `GET /api/v1/catalog-items/{id}` | 返回 `bangumiName`、`airDate`（如 `2026-10-03`）、`tags` 与 `lines` |
| 前端开发服务 | `http://localhost:5173/` | `HTTP 200` 正常可用 |

## 3. 使用说明

- 管理员登录后在「设置 → Bangumi 本季新番与题材元数据」点击 **「补齐题材与年份」**（或下次运行 `bangumi.sync` 自动执行），即可通过 Bangumi v0 接口批量补齐本季番剧的题材标签、详细简介与首播年份，随后首页「热门题材探索」、番剧索引动态题材/年份筛选与详情页题材胶囊会自动生效。
