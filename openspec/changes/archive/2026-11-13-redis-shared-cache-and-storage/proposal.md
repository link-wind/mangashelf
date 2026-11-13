## Why

docs/distributed-evolution.md 2.2/2.3：多实例下各 API 的 JVM 内存缓存（分区、发现流）互不相通，同一章节被并发打开时各实例重复回源，上游限流按实例计数无法全局约束。Redis 进入目标架构承担共享元数据缓存、全局限流与重复回源合并；页图缓存维持磁盘实现（多实例共享卷），S3 已是正式产物存储。业务事实不进 Redis。

## What Changes

- compose 新增 Redis 服务（无持久化、内存上限、LRU 淘汰）；后端接入 Spring Data Redis（Lettuce）。
- 共享元数据缓存：漫画首页分区/发现流/哔哩目录索引迁移到 Redis（键含源、资源、语言与规则版本，TTL 沿用现有 10 分钟），JVM 缓存降为短时一级缓存。
- 全局限流：解析类任务提交与 MangaDex 图片代理的并发闸门改为 Redis 计数限流（跨实例总量约束），实例内 Semaphore 保留作为一级保护。
- 重复回源合并：同一章节页图/详情并发请求经 Redis 短期锁共享一次加载结果（已有磁盘缓存未命中时）。
- Redis 不可用时降级：缓存读写失败按无缓存处理并告警计数，限流失败时放行实例内闸门（缓存可重建，业务事实不依赖 Redis）。
- 每实例 JVM 缓存与哔哩磁盘缓存、MangaDex 代理磁盘缓存保持现状（多实例共享卷命中）。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `manga-sources`：首页/分区缓存要求扩展为跨实例共享（Redis），并增加缓存故障降级要求。

## Impact

- compose：新增 redis 服务（`redis:7-alpine`，`--maxmemory 256mb --maxmemory-policy allkeys-lru`）
- 后端：`server/pom.xml`（spring-boot-starter-data-redis）、`external/manga`（SectionsService、DiscoveryService、BilibiliReaderService 目录缓存、MangaDex 代理限流）、`application.yml`（redis 连接与开关）
- 无数据库迁移；无前端改动
- 风险：Redis 单点故障的回源压力（降级路径显式处理）；序列化开销（仅缓存 JSON 元数据）
