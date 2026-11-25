# benchmark-db-and-hotspot-cache 验证记录

- 日期：2026-11-25
- 代码基线：`main` `a116100` + 本变更改动（V29 读路径索引 + 列表投影精简 + 批量聚合 + `HotspotCache`）
- 控制变量（三阶段完全一致）：
  - API：本机 Java 25.0.5（`-Xms256m -Xmx512m`，Spring Boot 4.1.2，Tomcat/HikariCP 默认池，Redis/Embedding 关闭）监听 `:18088`
  - DB：Docker Desktop `pgvector/pgvector:pg18`（PostgreSQL 18.6，共享 3918 MiB RAM）
  - 数据规模：`external_catalog_items = 7,512` 行、`bangumi_subjects = 114` 行、`manga_catalog_items = 30` 行、`works = 4` 行
  - 压测参数：`scripts/bench/run_load_bench.py --vus 24 --duration 12 --warmup 2`（与 `scripts/bench/k6-workload.js` 同权重混合流量）

## 1. 三阶段并发压测总体对比（24 VUs × 12s）

| 阶段 | 总请求数 | 错误率 | 总 RPS | 相对基线 | p50 (ms) | p95 (ms) | p99 (ms) | 平均响应体 | 出站带宽 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Stage 1：基线（优化前）** | 4,989 | 0.00% | **414.25** | 1.00× | 57.32 | **89.52** | 106.56 | 295.60 KB | 119.58 MB/s |
| **Stage 2：仅 DB 优化（V29 索引 + 列表去 `play_groups` + 批量聚合，缓存关闭）** | 16,885 | 0.00% | **1,405.48** | **3.39×** | 16.26 | **28.10** | 35.28 | **16.21 KB** | **22.24 MB/s** |
| **Stage 3：DB 优化 + `HotspotCache` 热点缓存** | 22,976 | 0.00% | **1,912.09** | **4.62×** | 11.19 | **22.36** | 29.15 | **16.14 KB** | 30.14 MB/s |

- **总体收益（Stage 1 → Stage 3）**：吞吐量由 **414.25 RPS 提升至 1,912.09 RPS（4.62×）**，p95 延迟由 **89.52 ms 降至 22.36 ms（-75.0%）**，平均响应体由 **295.60 KB 降至 16.14 KB（-94.5%）**。

## 2. 分端点三阶段延迟与吞吐对比

| 端点（权重） | Stage 1 p95 / RPS | Stage 2 p95 / RPS | Stage 3 p95 / RPS | 响应体变化 |
| --- | --- | --- | --- | --- |
| `catalog_first_page` (30%) | 92.69 ms / 120.73 | 26.06 ms / 413.11 | **15.77 ms / 564.49** | 495.2 KB → **12.0 KB** (-97.6%) |
| `catalog_episodes_deep` (15%) | 86.07 ms / 62.69 | 26.36 ms / 211.34 | **21.62 ms / 289.36** | 187.5 KB → **11.6 KB** (-93.8%) |
| `catalog_cat_ongoing` (15%) | 92.84 ms / 65.84 | 27.52 ms / 216.34 | **15.93 ms / 293.35** | 488.3 KB → **12.0 KB** (-97.5%) |
| `calendar` (20%) | 70.24 ms / 84.44 | 22.70 ms / 284.17 | **16.08 ms / 382.23** | 39.0 KB（不变） |
| `works_list` (10%) | 57.26 ms / 39.44 | 17.59 ms / 137.26 | **15.34 ms / 187.66** | 0.03 KB（不变） |
| `catalog_search` (`q=柯南`, 10%) | 96.97 ms / 41.10 | 36.21 ms / 143.25 | **31.72 ms / 194.99** | 376.3 KB → **11.7 KB** (-96.9%) |

## 3. PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)` 优化前后对比

| 查询 | Stage 1（优化前） | Stage 2（V29 索引后） | 关键变化 |
| --- | --- | --- | --- |
| `catalog_first_page` | `Seq Scan + top-N heapsort`<br>Buffers: `shared hit=2228`<br>Execution: **3.027 ms** | `Index Scan using idx_catalog_items_updated_cursor`<br>Buffers: **`shared hit=24`**<br>Execution: **0.050 ms** | Buffers **-98.9%**，SQL 耗时 **60.5× 提速** |
| `catalog_episodes_deep` (`OFFSET 480`) | `Seq Scan + top-N heapsort (265kB)`<br>Buffers: `shared hit=2228`<br>Execution: **3.444 ms** | `Index Scan using idx_catalog_items_episodes_desc`<br>Buffers: **`shared hit=506`**<br>Execution: **0.415 ms** | Buffers **-77.3%**，SQL 耗时 **8.3× 提速** |
| `catalog_cat_ongoing` (`category=30`) | `Seq Scan (Filter removed 7213)`<br>Buffers: `shared hit=2228`<br>Execution: **2.075 ms** | `Index Scan using idx_catalog_items_cat_updated`<br>Buffers: **`shared hit=23 read=2`**<br>Execution: **0.090 ms** | Buffers **-98.9%**，SQL 耗时 **23.1× 提速** |
| `catalog_search_trgm` (`%柯南%`) | `Seq Scan + quicksort`<br>Buffers: `shared hit=2228`<br>Execution: **3.495 ms** | `Seq Scan + quicksort`<br>Buffers: `shared hit=2228`<br>Execution: **3.254 ms** | 2 字中文无法命中 3-gram GIN（如实保留，端到端仍因去掉 `play_groups` 降了 62.7%） |

## 4. `HotspotCache` 实测命中率（`/actuator/prometheus`）

Stage 3 压测结束后直接采集 `http://127.0.0.1:18088/actuator/prometheus`（含 2s warmup）：

```text
mangashelf_hotspot_cache_requests_total{name="calendar",result="hit"} 5210.0
mangashelf_hotspot_cache_requests_total{name="calendar",result="miss"} 1.0
mangashelf_hotspot_cache_requests_total{name="catalog_count",result="hit"} 6503.0
mangashelf_hotspot_cache_requests_total{name="catalog_count",result="miss"} 2.0
mangashelf_hotspot_cache_requests_total{name="catalog_page",result="hit"} 11663.0
mangashelf_hotspot_cache_requests_total{name="catalog_page",result="miss"} 2.0
```

- 合计 **23,376 次命中 / 5 次回源**（命中率 **99.98%**），单 Key 并发回源合并防击穿有效生效。

## 5. 回归验证与已知限制

- **全量后端测试**：`./mvnw test` **55/55 通过**（含新增 `HotspotCacheTest`、8 个故障注入 IT、2 个 Hybrid Search Benchmark/权限隔离 IT）。
- **技术取舍与限制**：
  1. `HotspotCache` 当前为进程内有界缓存（TTL 30–60s），在多实例部署下各副本有最多 30–60s 的弱一致性窗口（同步完成后本实例立即失效，其余副本随短 TTL 刷新）；对强一致性要求高的用户写入路径（进度/收藏/发布）绝不走缓存。
  2. 两字中文的 `ILIKE '%柯南%'` 无法利用 PostgreSQL `pg_trgm` 3-gram GIN 索引，若后续目录增长至十万级以上，建议切入 `search_documents`（V28）或启用 `pg_bigm` 扩展。
  3. 本次压测数据基于单机本机 JVM + Docker PostgreSQL（7,512 条真实番剧目录），用于同环境严格变量对照，不脱离条件宣称分布式极限 QPS。
