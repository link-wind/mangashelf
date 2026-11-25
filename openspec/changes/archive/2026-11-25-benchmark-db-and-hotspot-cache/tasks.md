# Tasks: benchmark-db-and-hotspot-cache

## 1. 压测脚本与优化前基线画像

- [x] 1.1 编写标准 `scripts/bench/k6-workload.js` 与零依赖并发压测及 `EXPLAIN (ANALYZE, BUFFERS)` 采集脚本 `scripts/bench/run_load_bench.py`
- [x] 1.2 在优化前代码上执行并发基线压测并采集核心 SQL 执行计划，写入 `docs/validation/benchmark-baseline.md`

## 2. 数据库索引与慢 SQL 读路径优化

- [x] 2.1 新增 Flyway 迁移 `V29__read_path_indexes.sql`（目录更新时间表达式复合索引、分类复合索引、集数降序索引、Bangumi `tags` GIN 索引、公开作品游标部分索引）
- [x] 2.2 优化 `ExternalCatalogRepository.page` 列表精简投影（跳过 `play_groups` JSONB）与 `genre` 的 `tags @> ARRAY[?]` 查询改写
- [x] 2.3 优化 `CatalogRepository.decorations()` 批量聚合消除逐行多层关联子查询
- [x] 2.4 重建/重启 API 并以相同并发负载重跑压测，记录 DB 优化后的 `EXPLAIN (ANALYZE, BUFFERS)` 与 p50/p95/RPS 变化

## 3. 热点缓存与击穿防护实测

- [x] 3.1 实现 `HotspotCache`（有界容量 + TTL + 单 Key 并发回源合并 + Micrometer 命中率指标 + 可开关配置）
- [x] 3.2 接入 `BangumiService.calendar()` 与 `ExternalCatalogService.listItems()` 首屏/计数，并在目录/Bangumi 同步完成路径挂接主动失效
- [x] 3.3 编写 `HotspotCacheTest` 与读路径回归测试，同负载重跑第 3 轮压测验证缓存收益

## 4. 可观测性模板与报告沉淀

- [x] 4.1 新增 `deploy/observability/prometheus.yml` 与 `deploy/observability/grafana-dashboard.json`，并在 `compose.yaml` 增加可选 `--profile obs`
- [x] 4.2 运行全量后端测试 `./mvnw test` 确认零回归
- [x] 4.3 将三阶段（基线 → DB 优化 → DB + 热点缓存）完整对比数据、查询计划与技术取舍写入 `docs/validation/benchmark-db-and-hotspot-cache.md` 并更新 `README.md`
