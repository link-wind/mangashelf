# Tasks: harden-reliable-jobs-and-fault-injection

## 1. Outbox 并发修复与生命周期治理

- [x] 1.1 修复 `OutboxRepository.claimBatch` SQL 缺少 `lease_token IS NULL` 的互斥漏洞
- [x] 1.2 为 `OutboxRepository` 补充按 `lease_token` 条件更新的 `markRetry`（带抖动退避）、延迟写入 `insertDelayed`、指标统计查询与历史清理 `deleteDispatchedOlderThan`
- [x] 1.3 更新 `JobDispatcher` 使用带令牌校验的 `markRetry`、接入 Micrometer 指标并增加已派发记录定期清理

## 2. Worker Fencing、单条恢复隔离与错误分类

- [x] 2.1 在 `JobRepository` 新增 `isLeaseValid`、`requeueOnTransientFailure` 与 `findExpiredLeases` / 单条恢复原子方法
- [x] 2.2 新增 `TransientJobException` 并在 `JobExecutor` 实现白名单瞬时错误退避重入队（永久错误保持直接 `FAILED`）
- [x] 2.3 重构 `LeaseRecoveryScanner` 为按单条任务独立小事务恢复，防止单条失败回滚整批
- [x] 2.4 改造 `AnimationVerifyHandler`：合并 `bindAssetsAndMedia` 与 `markRevisionStatus(READY)` 入短事务，写入前校验 `lease_token`，失效返回 `STALE_LEASE`

## 3. RabbitMQ DLQ 与可观测指标

- [x] 3.1 更新 `RabbitTopologyConfig` 声明 `mangashelf.jobs.dlx` 与 `content.dlq`，为 `content.resolve` / `content.process` / `content.index` 绑定 DLX
- [x] 3.2 更新 `JobConsumer`：损坏载荷/缺 `jobId` 的毒消息执行 `basicNack(tag, false, false)` 路由至 `content.dlq` 并记录指标
- [x] 3.3 新增 `JobMetrics` 组件，暴露 Outbox 与 Worker/Job 核心 Micrometer 指标

## 4. Testcontainers 故障注入集成测试

- [x] 4.1 在 `server/pom.xml` 添加 `test` scope 的 Testcontainers（PostgreSQL + RabbitMQ）依赖并验证可编译拉起
- [x] 4.2 实现 `OutboxReliabilityIT`：验证同事务写、租约互斥不重领、过期租约回收重派、Broker 故障补发、重复投递幂等与毒消息进 DLQ
- [x] 4.3 实现 `WorkerLeaseFencingIT`：验证双 Worker 并发抢占、Worker A 租约过期后被 Worker B 接管且 A 的 `commitResult` 与业务写入均被 Fencing 拒绝（含 `AnimationVerifyHandler`）、瞬时错误上限退避重试与永久错误直接终止

## 5. 验证与文档沉淀

- [x] 5.1 运行 `cd server && ./mvnw test` 确认全部单测与故障注入集成测试通过
- [x] 5.2 将实测场景、故障注入结果、指标截图/样本与已知限制写入 `docs/validation/harden-reliable-jobs-and-fault-injection.md`
