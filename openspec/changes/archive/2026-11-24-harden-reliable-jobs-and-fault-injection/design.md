# Design: harden-reliable-jobs-and-fault-injection

> 对应 `docs/DESIGN.md` 第 11 / 20 / 21 节，增量演进 `com.mangashelf.ingestion`、`com.mangashelf.animation` 与 `com.mangashelf.config.RabbitTopologyConfig`，不新增数据表、不改变既有业务模块结构。

## 1. Outbox SQL 修复与治理

### 1.1 `claimBatch` 互斥条件修复
原 SQL：
```sql
WHERE status = 'PENDING' AND next_attempt_at <= now()
   OR (status = 'PENDING' AND lease_token IS NOT NULL AND lease_until < now())
```
修复为：
```sql
WHERE status = 'PENDING'
  AND (
        (lease_token IS NULL AND next_attempt_at <= now())
     OR (lease_token IS NOT NULL AND lease_until < now())
  )
```
### 1.2 `markRetry` 令牌校验 + 指数退避 Jitter
- 新增 `markRetry(long id, UUID leaseToken, int maxAttempts)`，SQL 加上 `WHERE id = ? AND lease_token = ?`，防止已被回收重派的旧线程误加 `attempts`；
- 退避公式：`LEAST(POWER(2, attempts), 30) + random() * 2` 秒（由 PostgreSQL 或 Java 计算传入延迟毫秒数，确保不同实例重试错峰）。
- 新增 `insertDelayed(UUID jobId, int dispatchNo, String payload, Duration delay)`，支持瞬时错误退避重派直接写入 `next_attempt_at = now() + delay`。
- 新增 `deleteDispatchedOlderThan(Duration retention)`，在 `JobDispatcher` 中每日/每小时清理 7 天前已 `DISPATCHED` 的记录。

## 2. 租约保护与恢复隔离

### 2.1 `AnimationVerifyHandler` 租约校验
参考 `ChapterParseHandler` 与 `JobRepository`：
- 在 `JobRepository` 提供 `boolean isLeaseValid(UUID jobId, UUID leaseToken)`（`SELECT 1 FROM jobs WHERE id = ? AND status = 'RUNNING' AND lease_token = ?`）；
- `AnimationVerifyHandler` 在 S3 上传产物完成后，在一个短事务内：
  1. 校验 `jobs.isLeaseValid(job.id(), ctx.leaseToken())`，不通过则抛出/返回 `STALE_LEASE`（不写 `animation_revisions`）；
  2. 同一事务内完成 `animations.bindAssetsAndMedia(...)` 与 `animations.markRevisionStatus(revision.id(), READY, null, null)`。

### 2.2 `LeaseRecoveryScanner` 单条独立事务
- 移除方法级 `@Transactional`，先查出待检查的过期租约 ID 列表（`findExpiredLeases(50)`），再逐条通过 `TransactionTemplate` 在独立短事务内执行：条件更新 `RUNNING -> QUEUED`（或超限置 `FAILED`）+ 写 `outbox_events` + `appendEvent`。单条失败仅打日志，不影响同批次其他过期任务恢复。

### 2.3 显式白名单瞬时错误（`TransientJobException`）
- 新增 `com.mangashelf.ingestion.TransientJobException`（带 `errorCode` 与 `message`）；
- `JobExecutor.runClaimed` 捕获 `TransientJobException` 时：
  - 若 `job.attempt() < job.maxAttempt()`：在单事务内调用 `jobs.requeueOnTransientFailure(job.id(), token, code, msg)`（仅当 `status = 'RUNNING' AND lease_token = ?` 时置回 `QUEUED`、清租约并记错误原因），并插入带指数退避 + jitter 的 `outbox_events` 与进度事件；
  - 若已达上限（`attempt >= maxAttempt`）或遇到非 `TransientJobException`：直接 `commitFailure` 置 `FAILED`。
- 该机制严格遵守 `AGENTS.md`：不对权限、格式、参数或通用 `Exception` 自动重试。

## 3. RabbitMQ DLQ 与毒消息路由

- `RabbitTopologyConfig` 声明：
  - `DirectExchange("mangashelf.jobs.dlx", true, false)`
  - `Queue("content.dlq")`（durable）与绑定 routing key `content.dlq`
  - 三条主队列 `content.resolve` / `content.process` / `content.index` 构建时附加：
    - `.deadLetterExchange(JOBS_DLX)`
    - `.deadLetterRoutingKey(QUEUE_CONTENT_DLQ)`
- `JobConsumer.consume` 细化异常处理：
  - 反序列化 `JobMessage` 失败或 `msg == null || msg.jobId() == null` 时视为毒消息：计数 `mangashelf.jobs.dlq.routed`，调用 `channel.basicNack(tag, false, false)` 路由至 `content.dlq`；
  - 合法消息交由 `executor.execute(msg)` 后 `basicAck`（业务失败已由 `JobExecutor` 落库，避免重复投递风暴）。

## 4. Micrometer 指标设计

新建轻量 `JobMetrics` 组件（依赖 `MeterRegistry`，无额外第三方依赖）：
- `mangashelf.outbox.pending`（Gauge，按需查询/缓存待派发数）与 `mangashelf.outbox.oldest_pending_seconds`（Gauge）
- `mangashelf.outbox.dispatch.total`（Counter，tag: `result=ack|nack|error|stale`）
- `mangashelf.outbox.confirm.duration`（Timer）
- `mangashelf.jobs.total`（Counter，tag: `kind`, `outcome=succeeded|failed|cancelled|retry|stale_rejected`）
- `mangashelf.jobs.duration`（Timer，tag: `kind`, `outcome`）
- `mangashelf.jobs.lease.recovered`（Counter）
- `mangashelf.jobs.lease.stale_rejected`（Counter）
- `mangashelf.jobs.dlq.routed`（Counter）

## 5. Testcontainers 故障注入测试设计

在 `server/pom.xml` 引入 `test` 依赖：`org.testcontainers:testcontainers-bom`（或 Spring Boot 4 管理的 `spring-boot-testcontainers` + `testcontainers-postgresql` + `testcontainers-rabbitmq` + `testcontainers-junit-jupiter`）。
- 使用 `pgvector/pgvector:pg18` 与 `rabbitmq:4-management`（与 `compose.yaml` 镜像版本一致，避免本地重复拉大镜像）；
- 在 `OutboxReliabilityIT` 和 `WorkerLeaseFencingIT` 中直接针对真实容器运行完整 Flyway 迁移与 Spring 上下文（测试环境禁用 Embedding / 外部网络同步），通过确定性同步点（`CountDownLatch` / 显式缩短租约 / 直接回退 DB `lease_until` / 模拟失败回调）稳定复现全部 6 个故障场景，避免 flaky sleep。
