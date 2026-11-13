## 1. 基础设施与接入

- [x] 1.1 compose 新增 redis 服务（无持久化、256MB LRU）；pom 加 spring-boot-starter-data-redis；application.yml 加 redis 配置与 `mangashelf.redis.enabled` 开关
- [x] 1.2 实现 `SharedCacheHelper`：get/put/incrMinute/tryLock 封装，异常降级 + 告警计数，超时 500ms/1s
- [x] 1.3 真实 Redis 验证：读写 TTL、分钟计数、SETNX 短锁、故障降级（停 redis 后业务可用）

## 2. 共享元数据缓存

- [x] 2.1 `MangaSectionsService` 迁移到 Redis 共享缓存（键含源/语言，TTL 10 分钟），JVM 缓存降为一级
- [x] 2.2 `MangaDiscoveryService` 综合榜结果缓存（键含源集合与语言）
- [x] 2.3 `BilibiliReaderService` 目录/详情元数据缓存写入 Redis（磁盘缓存保持一级）
- [x] 2.4 验证：双实例命中同一缓存（第二实例不回源）；规则版本变化产生新键

## 3. 全局限流与回源合并

- [x] 3.1 MangaDex 图片代理加 Redis 分钟计数限流（全局 5 req/s），实例内 Semaphore 保留
- [x] 3.2 哔哩目录/详情与 MangaDex 图片未命中时加 Redis 短锁合并并发回源
- [x] 3.3 验证：并发打开同章节只回源一次；限流超阈值返回 429；Redis 停用时限流放行

## 4. 验证与收尾

- [x] 4.1 双实例演练：2 个 API 进程交替请求首页，缓存命中一致
- [x] 4.2 Redis 故障演练：停 redis，首页/详情/图片代理全部可用且有告警计数
- [x] 4.3 `mvnw test`、Python 套件、`npm run build` 通过
- [x] 4.4 验证结果写入 `docs/validation/redis-shared-cache-and-storage.md`
