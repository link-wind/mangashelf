# harden-reliable-jobs-and-fault-injection 验证记录

- 日期：2026-11-24
- 基线：`main` `1e3e1c8` + 本变更改动
- 环境：macOS（Java 25.0.5 / Spring Boot 4.1.2 / Testcontainers 2.0.5）+ 真实 Docker 容器 `pgvector/pgvector:pg18`（PostgreSQL 18.6，完整 27 个 Flyway 迁移）+ `rabbitmq:4-management`

## 验收场景与实测结果

| 场景 | 测试类 / 方法 | 实测结果 |
| --- | --- | --- |
| Outbox 同事务写 + Broker 故障退避 + 恢复补发 + 指标计数 | `OutboxReliabilityIT.transactionalOutboxRecoversAfterBrokerFailureAndTracksMetrics` | ✅ 通过：模拟 Broker 异常后记录保持 `PENDING`（`attempts=1`，释放租约）；恢复派发后消息到达真实 `content.process`，`mangashelf.outbox.dispatch.total{result=error}` 与 `{result=ack}` 计数精确递增 |
| Outbox 领取租约互斥 + 过期回收 + 旧令牌拒绝 | `OutboxReliabilityIT.unexpiredOutboxLeaseBlocksConcurrentClaimAndExpiredLeaseRecovers` | ✅ 通过：修复 `claimBatch` 缺少 `lease_token IS NULL` 漏洞后，实例 A 持有 30s 租约期间实例 B `claimBatch` 返回空；回退 `lease_until` 过期后实例 B 回收并换发新令牌，实例 A 旧令牌调用 `markDispatched`/`markRetry` 均返回 `false` |
| Confirm 后崩溃引发重复投递幂等 | `OutboxReliabilityIT.duplicateDeliveryAfterConfirmCrashIsIdempotent` | ✅ 通过：同一 `JobMessage` 连续消费 2 次，最终 `SUCCEEDED`、`attempt=1`、业务副作用计数严格为 1 |
| 毒消息隔离进入 DLQ 且不阻塞合法任务 | `OutboxReliabilityIT.poisonMessageRoutesToDlqWithoutBlockingValidMessages` | ✅ 通过：损坏 JSON 被 `JobConsumer` 执行 `basicNack(tag, false, false)` 路由至真实 `content.dlq`、`mangashelf.jobs.dlq.routed` +1，同队列后续合法任务正常完成 |
| 4 Worker 并发争抢同一任务 | `WorkerLeaseFencingIT.concurrentWorkersClaimAtMostOneLeaseHolder` | ✅ 通过：4 线程同步起跑调用 `jobs.tryClaim`，恰有 1 线程获 `CLAIMED`，其余 3 线程获 `ACTIVE_LEASE`，最终 `attempt=1` |
| 过期 Worker 业务写入与提交被 Fencing 拒绝（含 `AnimationVerifyHandler`） | `WorkerLeaseFencingIT.expiredWorkerCommitAndAnimationRevisionWriteAreRejectedByFencing` | ✅ 通过：Worker A 领取后租约过期 → `LeaseRecoveryScanner` 原子恢复写 dispatch 2 → Worker B 接管写入 `duration_ms=12000` 并 `SUCCEEDED` → Worker A 持旧令牌尝试覆写 `duration_ms=99999` 与 `commitResult` 均被拒绝，`stale_rejected` 计数递增，最终保留 Worker B 结果 |
| 白名单瞬时错误退避重试 vs 永久错误直接终止 | `WorkerLeaseFencingIT.transientErrorRetriesUpToMaxAttemptWhilePermanentErrorFailsImmediately` | ✅ 通过：`TransientJobException` 第 1/2 次退回 `QUEUED`（`RETRY_WAIT`）并同事务写延迟 `outbox_events`，第 3 次达 `max_attempt` 后置 `FAILED`；`IllegalArgumentException`（永久错误）`attempt=1` 直接 `FAILED` 且不写重试 outbox |
| 恢复扫描单条独立事务隔离 | `WorkerLeaseFencingIT.recoveryScannerHandlesMultipleExpiredLeasesIndependently` | ✅ 通过：同批次含超限与可重试两条过期任务，分别准确置 `FAILED (LEASE_EXPIRED)` 与 `QUEUED` |
| 全量测试套 | `./mvnw test` | ✅ **51/51 通过**（43 原单测 + 8 真实容器集成测试，Failures: 0，Errors: 0，总耗时 13.4s） |

## 暴露的 Micrometer 指标清单（`/actuator/prometheus`）

- `mangashelf.outbox.pending`（Gauge）
- `mangashelf.outbox.oldest_pending_seconds`（Gauge）
- `mangashelf.outbox.dispatch.total{result=ack|nack|error|stale}`（Counter）
- `mangashelf.outbox.confirm.duration`（Timer）
- `mangashelf.jobs.total{kind,outcome=succeeded|failed|cancelled|retry|stale_rejected}`（Counter）
- `mangashelf.jobs.duration{kind,outcome}`（Timer）
- `mangashelf.jobs.lease.recovered`（Counter）
- `mangashelf.jobs.lease.stale_rejected`（Counter）
- `mangashelf.jobs.dlq.routed`（Counter）

## 升级与已知限制

- **已有 RabbitMQ 环境的队列参数变更**：本次为 `content.resolve` / `content.process` / `content.index` 新增了 `x-dead-letter-exchange=mangashelf.jobs.dlx` 与 `x-dead-letter-routing-key=content.dlq`。如果升级已运行过旧版的 RabbitMQ 实例，需在重启前先删除旧空队列（或通过 RabbitMQ Policy 注入 DLX），否则 AMQP 声明会报 `PRECONDITION_FAILED`。
- **Testcontainers 离线运行配置**：`server/pom.xml` 中为 `maven-surefire-plugin` 设置了 `TESTCONTAINERS_RYUK_DISABLED=true`，配合 `ReliabilityContainersBase` 的 JVM shutdown hook 自动停止容器，避免受限网络环境拉取 `testcontainers/ryuk` 超时；前提是本机 Docker 已存在 `pgvector/pgvector:pg18` 与 `rabbitmq:4-management` 镜像。
- **单机 `JobDispatcher` 历史清理**：已派发 outbox 清理任务默认每小时清理 7 天前 `DISPATCHED` 记录，多实例并发执行 `DELETE` 幂等安全。
