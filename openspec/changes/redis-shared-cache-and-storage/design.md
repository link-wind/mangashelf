## Context

动机见 proposal.md，依据 docs/distributed-evolution.md 2.2（Redis 边界）与 DESIGN.md 第 11、20 节。当前实现事实（2026-11-13 核验）：

- 全仓无 Redis 依赖与代码；`MangaSectionsService` 用 `ConcurrentHashMap`（TTL 10 分钟）做分区缓存，每实例独立；`MangaWarmup` 每实例启动预热。
- `BilibiliReaderService`：磁盘缓存（metadata/ + pages/，TTL + 512MB 容量）+ 实例内 `Semaphore(4)` 下载闸门 + JVM Object 锁做单飞；磁盘卷 compose 已挂共享卷（多实例可命中）。
- MangaDex 图片代理：磁盘缓存（manga-cache，key=sha256(url)）+ 实例内 `Semaphore(3)`（GATE）。
- 发现流 `MangaDiscoveryService` 实时融合各源榜单（未缓存）；综合榜相似度函数本地计算。
- SSE `JobEventsStream` 轮询数据库（每连接虚拟线程），已支持游标补读；前端未消费 SSE（HTTP 轮询）。

## Goals / Non-Goals

**Goals:**

- 分区/发现流缓存跨实例共享，重复回源减少。
- 解析提交与图片代理并发跨实例全局约束。
- Redis 故障时业务不受影响（显式降级）。

**Non-Goals:**

- 不把 Session 迁到 Redis（Spring Session JDBC 已共享，保持现状）。
- 不改页图磁盘缓存与 S3 产物存储（多实例共享卷即可命中）。
- 不做 Redis Pub/Sub SSE 唤醒（前端未消费 SSE，等前端接入时再评估）。
- 不引入 Spring Cache 抽象或多级缓存框架；直接用 StringRedisTemplate 封装一个小助手类。
- 不做缓存一致性保障（TTL 失效 + 后台刷新即可，与现状语义一致）。

## Decisions

1. **接入方式：StringRedisTemplate + 自封装 `SharedCacheHelper`，不用 Spring Cache 注解。**
   三个使用点（分区缓存、发现流、哔哩目录缓存）语义各异（对象缓存/计数限流/短锁），
   注解抽象反而绕弯；一个 ~100 行助手类封装 get/put/incr/tryLock 与异常降级，显式可控。

2. **缓存键规范：`ms:v1:{capability}:{source}:{resource}:{lang}`。**
   版本前缀 `v1` 用于将来缓存结构升级整体失效；规则版本参与 key（规则源更新即新键）。
   值为 JSON（现有 record 直接序列化）；TTL 沿用 10 分钟（分区）与哔哩现有目录 TTL。
   Cookie/登录态不进键——哔哩目录缓存目前匿名可用，与现状一致。

3. **限流：Redis INCR+EXPIRE 滑窗计数，键按分钟粒度。**
   MangaDex 图片代理：全局 5 req/s（上游限制），键 `ms:v1:ratelimit:mangadex-img:{epochMinute}`，
   INCR 后超阈值则等待/拒绝（代理返回 429）。解析任务提交限流沿用任务系统既有约束
   （dedup key + 队列），不额外加 Redis 层——避免重复建设。
   实例内 Semaphore 保留：一级保护避免 Redis 往返成为瓶颈。
   备选：令牌桶 Lua 脚本——分钟粒度计数已满足"跨实例总量约束"，Lua 增加复杂度。

4. **重复回源合并：Redis SETNX 短锁（30s TTL）。**
  哔哩目录/详情与 MangaDex 图片未命中磁盘缓存并发回源时，`SET NX` 抢锁，
   未抢到者轮询缓存（最多 ~5s）后回退自行回源（锁持有者失败时不阻塞他人）。
   已有 JVM Object 锁（单实例内）保留，Redis 锁只做跨实例层。

5. **降级：所有 Redis 读写包 try/catch，失败走"无缓存/放行"路径 + 告警计数（Micrometer counter）。**
   `mangashelf.redis.enabled=false` 时完全不接入（单机轻量部署可关闭）。
   连接超时 500ms、命令超时 1s，避免 Redis 故障拖慢请求线程。

6. **compose redis 服务：无持久化、256MB LRU。**
   缓存可重建，不需要 RDB/AOF；`allkeys-lru` 保证内存满时淘汰旧键而非拒绝写入。

## Risks / Trade-offs

- [Redis 故障引发回源风暴] → 降级到实例内缓存 + 告警；上游有自身限流，分钟计数键在恢复后自然约束。
- [JSON 序列化兼容] → 仅缓存自有 record；结构变化靠 `v1` 前缀整体失效。
- [Redis 锁持有者崩溃] → 30s TTL 自动释放；等待者超时后自行回源，不会永久等待。
- [分钟计数突刺] → 分钟边界双桶计数可平滑；当前按简单分钟计数接受突刺（上游 429 有退避）。

## Migration Plan

1. compose 加 redis；API/Worker 加连接配置（`mangashelf.redis.enabled` 开关）。
2. 部署后分区/发现流缓存自然写入 Redis；无数据迁移。
3. 回滚：开关关闭即回到实例内缓存；redis 容器可删除。

## Open Questions

无。SSE Pub/Sub 唤醒与 Session 迁移已明确不做（Non-Goals）。
