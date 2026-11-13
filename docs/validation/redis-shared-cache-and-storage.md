# redis-shared-cache-and-storage 验证记录

- 日期：2026-11-13
- 基线：`main` `11b841a` + 本变更工作区改动
- 环境：两个本地 jar API 实例（18081/18082，`--mangashelf.redis.enabled=true`）+ compose redis 容器（256MB LRU）

## 验证场景与结果

| 场景 | 结果 |
| --- | --- |
| compose redis 服务 | `redis:7-alpine`、maxmemory 268435456（256MB）、`allkeys-lru`、无持久化、健康检查通过 |
| SharedCacheHelper | get/put/incrMinute/tryLock 全部封装；异常降级计数 `mangashelf.redis.failures` |
| 发现流缓存写入 | 首次请求后 Redis 出现 `ms:v1:discovery:12`（29KB，TTL 584s≈10 分钟） |
| 跨实例命中 | 实例 B 首次请求发现流：8 行 0 错误、**93ms**（缓存命中），上游回源次数 0 |
| Redis 故障降级 | 停 redis 后实例 B 发现流仍返回 8 行 0 错误；降级计数 +2（get/put 各一次） |
| 恢复 | redis 重启后缓存写入恢复 |
| 图片代理限流计数 | 3 次代理请求 → `ms:v1:ratelimit:mangadex-img:{minute}` = 3 |
| 超限 429 | 当前分钟桶顶到 302（>300）→ 代理返回 **429** |
| 哔哩目录/详情 Redis 二级缓存 | 代码就位（detail/index 两级缓存 + 回源合并锁）；本环境哔哩源可达，detail 走磁盘缓存未触发 Redis 未命中路径 |
| 回源合并短锁 | tryLock 30s TTL + 5×400ms 等待重读 + 锁持有者失败不阻塞（代码路径）；实际效果依赖多实例并发回源时机，本环境未构造出并发未命中 |
| 后端测试 | 43/43 通过 |
| Python/前端 | 未触及（无前端改动；processing 无变化） |

## 实施发现

- **Maven 新依赖 403**：JVM 继承 macOS 系统代理后，Maven resolver 的 native HttpClient 经
  Clash 下载新依赖稳定 403（同 URL curl 200）。解法 `MAVEN_OPTS="-Dmaven.resolver.transport=wagon"`
  （wagon transport 绕过）。已写入项目记忆。
- **Lettuce API 差异**：Spring Data Redis（Boot 4）的 `LettuceClientConfiguration` 无
  `connectTimeout` builder 方法，已移除（commandTimeout 覆盖主要场景）。
- **redis 容器默认不暴露端口**：本地裸 JVM 实例连不上，compose 加了 `REDIS_PORT` 映射
  （容器间走服务名不受影响）。

## 已知限制

- 回源合并短锁未在真实并发未命中下观测（需要两实例同时磁盘+Redis 双未命中同一资源；
  逻辑简单且失败安全——锁超时自动放行）。
- 哔哩 detail/index 的 Redis 值直接序列化 `MangaSource.Detail`/`StoredIndex` record，
  结构变化依赖键前缀 `v1` 整体失效。
- 分区缓存未在本次演练中触发键写入（腾讯源网络受限、哔哩 sections 走了 rank 路径）；
  发现流缓存已验证同机制。
- Session 未迁移（Non-Goal，Spring Session JDBC 保持）；SSE Pub/Sub 唤醒未做（前端未消费 SSE）。
