# Proposal: benchmark-db-and-hotspot-cache

## Why

在完成异步任务可靠性加固（`87d9cff`）与零 OCR 站内混合检索量化评测（`a116100`）之后，项目在**高并发读路径性能基线、数据库索引/SQL 实测优化与热点缓存收益验证**上仍处于空白：

1. **缺少并发压测基线**：现有 `scripts/bench-m5.sh` 仅用单线程 `curl` 测试 2 部作品的吞吐，没有多并发压测脚本（k6 / 并发 Runner）、没有固定规模测试负载、也没有记录 API p50/p95/p99、RPS 与数据库 `EXPLAIN (ANALYZE, BUFFERS)` 画像。
2. **高频读路径存在明显可优化热点**：代码审计发现多条核心查询在万级目录与高并发下会触发全表扫描、大字段传输或嵌套子查询：
   - `ExternalCatalogRepository.page`（首页“最近更新”与番剧索引最高频接口）：① `SELECT *` 把列表页完全不需要的大体量 `play_groups` JSONB 整列拖出并序列化；② `ORDER BY COALESCE(resource_updated_at, synced_at) DESC, id DESC` 与 `ORDER BY episode_count DESC, id DESC` 均无对应索引，每次翻页全表扫描排序；③ `genre` 题材过滤用 `EXISTS (SELECT 1 FROM unnest(tags) ...)` 且 `bangumi_subjects.tags` 缺 GIN 索引；④ 每次翻页无条件执行 `count(*)` 全表计数；
   - `CatalogRepository.listVisible` 缺公开作品游标复合部分索引，`decorations()` 对每页作品执行 3 个多层嵌套关联子查询；
   - `GET /api/v1/calendar` 与无筛选首屏 `GET /api/v1/catalog-items` 属于典型低频写、超高频读热点，目前每次请求直击 PostgreSQL。
3. **缺少直观的可观测看板模板**：`/actuator/prometheus` 指标已完备，但缺少一键拉起的可选 Prometheus + Grafana 看板配置来配合压测展示 API / JVM / HikariCP / Outbox / Worker / Search 指标。

对应设计依据：`docs/DESIGN.md` 第 8.4 / 20 / 21 节及《MangaShelf｜项目定位与性能优化规划》第 5、6、9、11 节。原则：**先跑基线再优化，每一步保留同负载前后对比数据，不虚报提升。**

## What Changes

1. **建立固定压测套件与基线报告（阶段 1）**：
   - 提供标准 `scripts/bench/k6-workload.js`（供 k6 / Docker `grafana/k6` 运行）及零外部依赖并发压测脚本 `scripts/bench/run_load_bench.py`（覆盖首页日历、番剧目录翻页/深分页/题材筛选、作品列表、详情读取、目录搜索 5 大读路径 + 阅读进度更新写路径，自动采集 p50/p95/p99、RPS、错误率与 PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)`）；
   - 在**优化前代码**上运行完整并发压测并留存基线报告 `docs/validation/benchmark-baseline.md`。
2. **数据库索引与慢 SQL 优化（阶段 2）**：
   - 新增 Flyway 迁移 `V29__read_path_indexes.sql`：为 `external_catalog_items` 新增 `(COALESCE(resource_updated_at, synced_at) DESC, id DESC)` 表达式复合索引与 `(episode_count DESC, id DESC)` 索引；为 `bangumi_subjects(tags)` 新增 GIN 索引；为 `works` 新增公开可见游标部分索引 `(created_at DESC, id DESC) WHERE visibility = 'PUBLIC' AND status = 'PUBLISHED'`；
   - 优化 `ExternalCatalogRepository.page`：列表查询显式投影所需列（省略 `play_groups` 巨型 JSONB，详情页仍完整读取 `play_groups`），`genre` 筛选改写为 `tags @> ARRAY[?]` 以走 GIN 索引；
   - 重写 `CatalogRepository.decorations()`：将 3 组多层关联子查询拆改为按 `work_id IN (...)` 批量聚合查询，消除逐行嵌套开销；
   - 同负载重跑压测，对比优化前后 `EXPLAIN (ANALYZE, BUFFERS)` 与 p50/p95/RPS。
3. **热点缓存与击穿防护实测（阶段 3）**：
   - 新增轻量进程内热点缓存 `HotspotCache`（支持显式 TTL、容量上限、并发回源合并防击穿与写入/同步失效），按压测证实的热点接入 `GET /api/v1/calendar`、无筛选首屏 `GET /api/v1/catalog-items` 与目录总计数，并暴露 Micrometer 命中/未命中指标（`mangashelf.hotspot.cache.*`）；
   - 同负载重跑第 3 轮压测，量化“基线 → DB 优化 → DB 优化 + 热点缓存”三阶段真实对比。
4. **可选可观测性配置与文档沉淀（阶段 4）**：
   - 在 `deploy/observability/` 提供轻量 `prometheus.yml` 与 Grafana 预置 Dashboard JSON，并在 `compose.yaml` 挂载可选 `--profile obs`（不影响默认开发/生产栈）；
   - 沉淀完整三阶段对比报告至 `docs/validation/benchmark-db-and-hotspot-cache.md` 并同步更新 `README.md`。

## Capabilities

### New Capabilities

（性能基线、索引优化与热点缓存属于既有读路径的工程演进，归入 `manga-catalog` 规格。）

### Modified Capabilities

- `manga-catalog`：补充目录/作品读路径性能边界要求（列表不携带多余大字段、高频排序/筛选具备索引支撑、热点只读缓存具备明确 TTL 与同步失效语义）。

## Impact

- **数据库迁移**：新增 `V29__read_path_indexes.sql`（纯新增索引，零表结构破坏）。
- **后端代码**：`ExternalCatalogRepository`、`ExternalCatalogService`、`BangumiService`、`CatalogRepository`、`config/HotspotCache.java`。
- **脚本与运维**：`scripts/bench/k6-workload.js`、`scripts/bench/run_load_bench.py`、`deploy/observability/`、`compose.yaml`（可选 `obs` profile）。
