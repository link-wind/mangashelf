# Delta for upload-pipeline

## ADDED Requirements

### Requirement: Outbox 领取租约互斥与清理

Outbox 派发领取（`claimBatch`）MUST 仅选取未持有有效租约的待派发记录（`lease_token IS NULL AND next_attempt_at <= now()`）或租约已过期的记录（`lease_token IS NOT NULL AND lease_until < now()`）；确认派发与失败重试更新 MUST 匹配当前 `lease_token`。重试退避 MUST 包含抖动，并支持清理超过保留期的已派发历史记录。

#### Scenario: 未过期租约不被并发重复领取

- **WHEN** 实例 A 领取一批 `PENDING` outbox 记录且租约尚未过期，实例 B 同时调用 `claimBatch`
- **THEN** 实例 B 不会重复领取实例 A 持有有效租约的记录，实例 A 的 `lease_token` 不被覆盖

#### Scenario: 租约过期后可被回收重派

- **WHEN** 实例 A 领取 outbox 记录后崩溃且 `lease_until` 已过期，另一实例调用 `claimBatch`
- **THEN** 该记录被重新领取并分配新 `lease_token`，实例 A 若延迟到达则按旧令牌更新返回 0 行

### Requirement: 毒消息死信隔离与白名单瞬时重试

任务队列（`content.resolve` / `content.process` / `content.index`）MUST 绑定死信交换机与死信队列（`content.dlq`）；消费者收到无法反序列化或缺少必要字段的毒消息时 MUST 拒绝且不重入原队列（`basicNack(..., requeue=false)`），使其路由到 `content.dlq`。Worker MUST 仅对显式白名单的瞬时错误（`TransientJobException`）在未超过 `max_attempt` 时执行退避重入队（单事务更新任务状态并写入带延迟的 `outbox_events`）；权限、格式、参数与未声明异常 MUST 直接终止为 `FAILED`，不做全量自动重试。

#### Scenario: 毒消息进入死信队列

- **WHEN** 一条损坏的 JSON 载荷被投递到任务队列
- **THEN** 消费者拒绝该消息且不重回原队列，该消息出现在 `content.dlq` 中且不阻塞后续合法任务

#### Scenario: 瞬时错误退避重试与永久错误直接终止

- **WHEN** 处理器抛出 `TransientJobException` 且 `attempt < max_attempt`
- **THEN** 任务在单事务内退回 `QUEUED` 并写入延迟退避的 outbox 记录；当重试耗尽或抛出永久错误时任务直接置为 `FAILED`

### Requirement: 动画核验写入租约保护与单条恢复隔离

`AnimationVerifyHandler` 在提交媒体元数据与标记版本 `READY` 时 MUST 在同一短事务内校验任务租约令牌仍有效；租约失效时 MUST 拒绝写入并返回 `STALE_LEASE`。`LeaseRecoveryScanner` MUST 以单条任务独立事务恢复过期租约，单条任务恢复失败 MUST NOT 回滚同批其余任务的恢复。

#### Scenario: 过期动画核验写入被 Fencing 拒绝

- **WHEN** Worker A 执行 `animation.verify` 期间租约过期且被 Worker B 接管，随后 Worker A 尝试提交媒体元数据与 `READY` 状态
- **THEN** Worker A 的业务写入与结果提交均被拒绝，版本元数据与任务状态保持 Worker B 的有效结果

### Requirement: 异步任务可观测指标与故障注入验证

系统 MUST 通过 Micrometer 暴露 Outbox（待派发数量、最老待派发年龄、派发结果计数、发布确认耗时）与 Worker 任务（完成/失败计数、执行耗时、租约恢复计数、过期提交拒绝计数、死信计数）指标，并通过真实 PostgreSQL 与 RabbitMQ 集成测试自动化验证 Outbox 重派、重复投递幂等、双 Worker 抢占、租约过期 Fencing、毒消息入 DLQ 与错误分类重试。

#### Scenario: 故障注入集成测试全量通过

- **WHEN** 在真实 PostgreSQL 与 RabbitMQ 容器环境下运行 `OutboxReliabilityIT` 与 `WorkerLeaseFencingIT`
- **THEN** 全部故障注入场景按预期恢复或拒绝过期写入，对应 Micrometer 计数准确递增
