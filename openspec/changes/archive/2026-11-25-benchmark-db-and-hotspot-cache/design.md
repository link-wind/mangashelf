# Design: benchmark-db-and-hotspot-cache

> 对应 `docs/DESIGN.md` 第 8.4 / 20 / 21 节。严格遵循“先基线、再 DB 优化、最后热点缓存”的三阶段对照方法，同一负载与数据集下记录每一步真实收益。

## 1. 压测负载与基线口径

- **测试对象**：本地全栈 API（`:8080`）+ 真实 PostgreSQL 18（含当前 7512 条 `external_catalog_items`、114 条 `bangumi_subjects`、以及压测前可按需补足的公开作品/章节样本）+ Redis。
- **压测脚本双形态**：
  1. `scripts/bench/k6-workload.js`：标准 k6 负载脚本（支持 `k6 run` 或 Docker `grafana/k6`）；
  2. `scripts/bench/run_load_bench.py`：零第三方依赖并发压测执行器（Python `ThreadPoolExecutor` + HTTP Keep-Alive 连接复用），支持固定并发数（如 24 VU）、固定持续时长（如 15s）与固定混合请求分布：
     - 30% `GET /api/v1/catalog-items?limit=24`（首页/番剧索引首屏）
     - 15% `GET /api/v1/catalog-items?limit=24&sort=episodes&page=20`（集数排序深分页）
     - 15% `GET /api/v1/catalog-items?limit=24&category=30&status=ongoing`（分类+状态筛选）
     - 20% `GET /api/v1/calendar`（首页本季新番日历）
     - 10% `GET /api/v1/works?limit=20`（作品列表含 `decorations()`）
     - 10% `GET /api/v1/catalog-items?limit=24&q=柯南`（目录标题搜索）
- **数据库画像**：每次压测前自动对核心 4 条 SQL 执行 `EXPLAIN (ANALYZE, BUFFERS)`，记录计划节点类型（`Seq Scan` vs `Index Scan`）、实际耗时（Execution Time ms）与 Shared Buffers 命中/读取页数。

## 2. 数据库索引与 SQL 优化（`V29__read_path_indexes.sql`）

1. **番剧目录更新时间表达式复合索引**（解决默认列表 `Seq Scan + Sort`）：
   ```sql
   CREATE INDEX idx_catalog_items_updated_cursor
       ON external_catalog_items ((COALESCE(resource_updated_at, synced_at)) DESC, id DESC);
   ```
2. **番剧目录分类 + 更新时间复合索引**（解决按分类筛选翻页）：
   ```sql
   CREATE INDEX idx_catalog_items_cat_updated
       ON external_catalog_items (category_id, (COALESCE(resource_updated_at, synced_at)) DESC, id DESC);
   ```
3. **番剧目录集数降序索引**（解决 `sort=episodes` 全表排序）：
   ```sql
   CREATE INDEX idx_catalog_items_episodes_desc
       ON external_catalog_items (episode_count DESC, id DESC);
   ```
4. **Bangumi 标签 GIN 索引**（配合 `tags @> ARRAY[?]` 替代 `unnest` 子查询）：
   ```sql
   CREATE INDEX idx_bangumi_subjects_tags_gin
       ON bangumi_subjects USING gin (tags);
   ```
5. **公开作品可见性游标部分索引**（解决 `GET /api/v1/works` 全表扫描排序）：
   ```sql
   CREATE INDEX idx_works_public_published_cursor
       ON works (created_at DESC, id DESC)
       WHERE visibility = 'PUBLIC' AND status = 'PUBLISHED';
   ```
6. **列表投影精简**：`ExternalCatalogRepository.page` 将 `SELECT *` 改为显式列出列表所需列，以 `'' AS play_groups` 跳过平均数 KB 的 JSONB TOAST 解压与网络序列化（`findItem` 详情接口保持完整 `SELECT *`）。
7. **消除 `CatalogRepository.decorations()` 逐行嵌套子查询**：拆成 3 条按 `work_id = ANY(?)` 批量聚合查询，避免外层每行重复触发 3 次多表子查询。

## 3. 轻量热点缓存（`HotspotCache`）

- 在 `com.mangashelf.config.HotspotCache` 实现简洁、无额外大依赖的进程内有界 TTL 缓存（配合 `ConcurrentHashMap` + 单 Key `compute` 合并防击穿 + 显式容量上限与定时/惰性淘汰，必要时可由开关 `mangashelf.hotspot-cache.enabled` 一键启停用于压测 A/B 对照）。
- 覆盖经压测验证的高频低变只读路径：
  1. `calendar`（`GET /api/v1/calendar`，TTL 60s）—— `BangumiService.syncCalendar()` / `matchToCatalog()` 完成后调用 `invalidate("calendar")`；
  2. `catalog-count:*`（无搜索词目录总计数，TTL 60s）与 `catalog-first-page:*`（无游标/无搜索词首屏列表，TTL 30s）—— `CatalogSyncHandler` 同步完成后调用 `invalidatePrefix("catalog-")`；
- 暴露 Micrometer 指标：`mangashelf.hotspot.cache.requests{name,result=hit|miss}`。

## 4. 可选可观测性配置（`--profile obs`）

- 新增 `deploy/observability/prometheus.yml`（抓取 `api:8080/actuator/prometheus`）与 `deploy/observability/grafana-dashboard.json`（涵盖 API QPS/p95、HikariCP 连接池、Outbox 滞留、Worker 执行、Search 耗时与 Hotspot Cache 命中率），在 `compose.yaml` 以 `profiles: ["obs"]` 声明，不影响默认开发与生产启动。
