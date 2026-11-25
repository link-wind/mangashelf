# 验证报告：导航精简（「我的」+ 漫画双 Tab）、全流漫画搜索、分类分页索引与双端推荐优化

日期：2026-11-25  
关联变更：`openspec/changes/archive/2026-11-25-revamp-discovery-search-index-and-nav/`

## 1. 自动化测试验证

- **后端全量测试**（`maven:3-eclipse-temurin-25` + Testcontainers 真实 PostgreSQL 18 / RabbitMQ）：
  - `Tests run: 69, Failures: 0, Errors: 0, Skipped: 0`（含新增 `RecommendationServiceTest` 5 项、`MangaDiscoveryServiceTest` 跨流聚簇/熔断/作者打散等 10 项，以及全部既有可靠性与混合检索基准 IT）。
- **前端类型检查与生产构建**（`npm --prefix web run build`）：
  - `tsc -b && vite build` 零错误通过（`1.12s`）。
  - 路由级 `React.lazy` 拆包实测对比：
    - 改造前：单一主包 `dist/assets/index-*.js` = `1,012 KB`
    - 改造后：首屏主包 `dist/assets/index-*.js` = `279.67 KB`（gzip `89.04 KB`，降幅 **72.4%**）；`hls.js` 与播放壳（`useEpisodePlayback-*.js` = `608.23 KB`）仅在进入播放页按需加载；各主要页面 chunk 均在 `2.1–20.4 KB`。

## 2. 真实环境端到端接口实测（`http://localhost:8080` + PostgreSQL 18 + Redis）

| 场景 | 请求 | 实测结果 |
|---|---|---|
| 番剧按评分分页（第 1 页） | `GET /api/v1/catalog-items?page=1&limit=12&sort=rating` | `200`，`133 ms`，`page=1/627`，`total=7516`，返回 12 条 |
| 番剧按评分分页（第 2 页） | `GET /api/v1/catalog-items?page=2&limit=12&sort=rating` | `200`，`13 ms`，`page=2/627`，`total=7516`，返回 12 条 |
| 番剧搜索计数修正 | `GET /api/v1/catalog-items?page=1&limit=12&q=咒术` | `200`，`22 ms`，`total=5`，`items=5`（修复旧版 `count()` 漏传 `q` 问题） |
| 漫画全流搜索（冷态 1/2 次） | `GET /api/v1/manga-discovery/search?source=all&q=一人之下` | `200`，`5,038 ms` / `5,016 ms`（共享 5s 总截止，不再多源串行叠加至 13.5s），返回 4 条去重结果及 6 路流状态 |
| 漫画全流搜索（故障源熔断后第 3 次） | `GET /api/v1/manga-discovery/search?source=all&q=一人之下` | `200`，**`143 ms`**；超时源自动进入 5 分钟冷却跳过，正常流（腾讯/目录库/本地）秒级返回 |
| 漫画全流分类索引分页 | `GET /api/v1/manga-catalog?page=1&limit=12` | `200`，`14 ms`，`page=1/9`，`total=99`（发现流异步沉淀生效），支持全流与单源切换 |
| 动画推荐接口 | `GET /api/v1/recommendations/anime?limit=8` | `200`，`37 ms`，返回 8 条带理由推荐（如 `本季高分 7.9`）+ 12 条本季精选 |
| 漫画推荐接口 | `GET /api/v1/recommendations/manga?limit=8` | `200`，`877 ms`，候选 123 部，返回 8 条带理由推荐（如 `2 源热推 · 站内可读`） |
| 前端开发服务 | `http://localhost:5173/` | `HTTP 200` 正常可用 |

## 3. 已知限制

1. `bangumi_subjects.tags` 依赖 Bangumi 条目详情同步（仅日历接口不包含题材标签），当前本地库中尚未对全量条目拉取详情标签，因此 `/api/v1/catalog-items/genres` 返回空时前端平滑回退到预置高频题材列表；补齐 Bangumi 条目详情标签后会自动切为动态统计。
2. `copymanga`、`baozimh`、`hipmh` 在当前宿主机网络环境下存在上游域名超时/不可达情况，已通过共享 5s 截止与 5 分钟自动熔断冷却隔离，不影响腾讯、目录缓存及本地书库检索速度。
