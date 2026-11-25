# 验证报告：P0+P1 高清封面、统一 3:4 栅格、新版时刻表与浏览/播放增强

日期：2026-11-26  
关联变更：`openspec/changes/archive/2026-11-26-polish-schedule-hd-covers-and-layout/`

## 1. 构建与自动化测试

- **后端测试**（`maven:3-eclipse-temurin-25`）：
  - `RecommendationServiceTest`（含新增 `similarAnimePrefersSharedTagsAndCategory`）、`BangumiTitleMatchTest`、`BangumiCoverCacheTest`、`MangaDiscoveryServiceTest` 共 **21/21 通过**（`6.5s`）。
- **前端构建**（`npm --prefix web run build`）：
  - `tsc -b && vite build` 零错误通过（`1.01s`），首屏主包维持 `279.82 KB`（gzip `89.08 KB`），新版 `Schedule.js` 独立 chunk `11.77 KB`、`Home.js` `15.97 KB`。

## 2. 真实环境端到端实测（`http://localhost:8080` + `http://localhost:5173`）

| 验证项 | 请求 / 场景 | 实测结果 |
|---|---|---|
| 动画推荐高清封面 | `GET /api/v1/recommendations/anime?limit=8` | `200`，`13 ms`，返回封面统一为 `?size=large` |
| 播放页相似推荐 | `GET /api/v1/recommendations/anime/{id}/similar?limit=6` | `200`，`17 ms`，返回 6 部同分区/同题材可播番剧及推荐理由 |
| 完结补番精选 | `GET /api/v1/catalog-items?limit=10&page=1&status=completed&sort=rating` | `200`，`13 ms`，匹配 `5,164` 部完结番剧 |
| Bangumi `large` 高清封面与缓存 | `GET /api/v1/bangumi-covers/{subjectId}?size=large` | `200`，`7 ms`（命中持久化缓存），`66,739 B` (`image/jpeg`)；携带 `If-None-Match` 返回 `HTTP 304` |
| 前端开发服务 | `http://localhost:5173/` | `HTTP 200` 正常可用 |

## 3. 已知限制

- Bangumi 公共日历接口不含具体播出分钟时刻表，新版 `/schedule` 因此按「当日焦点可播 / 更多播映」分组，不伪造播出时钟。
- `bangumi_subjects.tags` 待 P2 补充 Bangumi v0 条目详情同步后可进一步丰富相似推荐的题材细粒度与动态题材计数。
