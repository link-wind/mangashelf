# MangaShelf 性能优化前基线报告（Stage 1 Baseline）

- 日期：2026-11-25
- 代码基线：`main` `a116100`（未应用 V29 索引、列表精简投影与热点缓存）
- 硬件与运行规格：
  - API：本机 Java 25.0.5（`-Xms256m -Xmx512m`，Tomcat 默认线程，HikariCP 默认池，Redis/Embedding 关闭）监听 `:18088`
  - DB：Docker Desktop `pgvector/pgvector:pg18`（PostgreSQL 18.6，容器共享 3918 MiB RAM）
  - 数据规模：`external_catalog_items = 7,512` 行、`bangumi_subjects = 114` 行、`manga_catalog_items = 30` 行、`works = 4` 行
- 压测参数：`scripts/bench/run_load_bench.py --vus 24 --duration 12 --warmup 2`（与 `scripts/bench/k6-workload.js` 同权重混合流量）

## 1. 总体并发基线（24 VUs × 12s）

| 指标 | 基线实测值 |
| --- | --- |
| 总请求数 / 成功数 / 错误率 | 4,989 / 4,989 / **0.00%** |
| 总吞吐（RPS） | **414.25 req/s** |
| 延迟 p50 / p95 / p99 | **57.32 ms / 89.52 ms / 106.56 ms** |
| 平均响应体大小 | **295.60 KB** |
| 应用层出站带宽 | **119.58 MB/s** |

## 2. 分端点基线明细

| 端点 | 权重 | 请求数 | RPS | p50 (ms) | p95 (ms) | p99 (ms) | 平均响应体 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `catalog_first_page` (`GET /api/v1/catalog-items?limit=24`) | 30% | 1,454 | 120.73 | 63.78 | 92.69 | 109.51 | **495.2 KB** |
| `catalog_episodes_deep` (`sort=episodes&page=20`) | 15% | 755 | 62.69 | 57.35 | 86.07 | 103.74 | **187.5 KB** |
| `catalog_cat_ongoing` (`category=30&status=ongoing`) | 15% | 793 | 65.84 | 64.64 | 92.84 | 113.84 | **488.3 KB** |
| `calendar` (`GET /api/v1/calendar`) | 20% | 1,017 | 84.44 | 44.45 | 70.24 | 80.05 | 39.0 KB |
| `works_list` (`GET /api/v1/works?limit=20`) | 10% | 475 | 39.44 | 33.10 | 57.26 | 70.45 | 0.03 KB |
| `catalog_search` (`q=柯南`) | 10% | 495 | 41.10 | 66.15 | 96.97 | 119.45 | **376.3 KB** |

## 3. 核心慢 SQL 执行计划画像（`EXPLAIN (ANALYZE, BUFFERS)`）

| 查询 | 扫描方式 | 实际扫描/过滤 | Shared Buffers Hit | Execution Time |
| --- | --- | --- | --- | --- |
| `catalog_first_page` | `Seq Scan + top-N heapsort` | 扫描全表 7,512 行 | **2,228 pages (~17.4 MB)** | 3.027 ms |
| `catalog_episodes_deep` | `Seq Scan + top-N heapsort (265kB)` | 扫描全表 7,512 行 | **2,228 pages (~17.4 MB)** | 3.444 ms |
| `catalog_cat_ongoing` | `Seq Scan + top-N heapsort` | 过滤丢弃 7,213 行 | **2,228 pages (~17.4 MB)** | 2.075 ms |
| `catalog_search_trgm` (`%柯南%`) | `Seq Scan + quicksort` | 过滤丢弃 7,476 行 | **2,228 pages (~17.4 MB)** | 3.495 ms |

## 4. 基线诊断结论（按瓶颈影响排序）

1. **巨型 JSONB 传输与序列化放大（头号瓶颈）**：列表接口使用 `SELECT *` 返回全部 `play_groups` JSONB，使单次 24 条列表响应膨胀至 **500 KB**，24 并发下应用出站带宽高达 **119.6 MB/s**，严重消耗 Jackson 序列化 CPU 与网络 I/O。
2. **高频排序与筛选缺失复合索引**：4 条核心目录查询全部退化为 `Seq Scan`（每次读取 2,228 个 8KB 缓存页 ≈ 17.4 MB）+ 内存排序，且每次列表翻页均额外执行一条全表 `count(*)`。
3. **低频写、超高频读接口直击数据库**：`/api/v1/calendar`（20% 流量）和首页无筛选 `/api/v1/catalog-items`（30% 流量）每次请求重复查库、重复拼装 JSON。
