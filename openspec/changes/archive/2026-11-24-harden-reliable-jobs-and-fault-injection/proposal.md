# Proposal: harden-reliable-jobs-and-fault-injection

## Why

当前后端已完成 Transactional Outbox、Worker 租约心跳、三队列路由与原子恢复的基础框架（V25 `harden-job-lease-and-routing`），但对照生产级可靠异步任务要求与真实源码逐项排查，仍存在以下具体缺口与隐患：

1. **Outbox 领取并发窗口 Bug**：`OutboxRepository.claimBatch` 第一分支 `status = 'PENDING' AND next_attempt_at <= now()` 缺少 `AND lease_token IS NULL`，导致已被某实例领取且处于发送确认窗口内的 `PENDING` 记录仍会被下一轮扫描或另一实例重复领取并覆盖 `lease_token`；同时 `markRetry` 未按 `lease_token` 条件更新，退避无 jitter，且缺少已派发历史记录定期清理。
2. **业务写入租约保护存在遗漏**：`AnimationVerifyHandler` 的媒体元数据绑定（`bindAssetsAndMedia`）与状态切换（`markRevisionStatus(READY)`）分在事务内外两步执行，且未对齐 `ChapterParseHandler` 的租约令牌校验（`STALE_LEASE`）。
3. **恢复批处理故障传播与错误未分类**：
   - `LeaseRecoveryScanner.recoverExpiredLeases` 在方法级 `@Transactional` 内循环处理整批，单条任务异常会回滚同批其他任务的恢复；
   - `JobExecutor` 未区分白名单瞬时错误（网络/上游超时/S3 5xx）与永久错误（格式/权限/参数），无法在不违反 `AGENTS.md`（不对全量 `Exception` 自动重试）前提下对瞬时故障做有上限退避重试。
4. **RabbitMQ 毒消息静默吞掉**：`RabbitTopologyConfig` 三队列未绑定死信交换机/死信队列（DLQ），`JobConsumer` 遇到损坏 JSON 等无法反序列化的毒消息时直接 `basicAck` 丢弃，缺乏可观测隔离。
5. **核心业务可观测性与故障注入验证缺失**：`/actuator/prometheus` 已开启但缺少 Outbox/Job 关键业务指标；`server/src/test` 中缺少基于真实 PostgreSQL + RabbitMQ（Testcontainers）的自动化故障注入集成测试（此前 `STALE_LEASE` 竞态仅做代码路径检查，未在真实并发窗口验证）。

对应设计依据：`docs/DESIGN.md` 第 11 节（异步处理模型与恢复）、第 20 节（观测与运维）、第 21 节（验证与测试）及根目录 `AGENTS.md`。

## What Changes

1. **Outbox 并发修复与治理**：修复 `OutboxRepository.claimBatch` 缺少 `lease_token IS NULL` 的 SQL 漏洞；`markRetry` 改为按 `lease_token` 条件更新并加入退避 jitter；新增低频清理已派发旧记录方法。
2. **Worker Fencing 与单条事务恢复**：
   - `AnimationVerifyHandler` 合并媒体绑定与状态更新入同一短事务并增加 `jobs.lease_token` 校验（失效返回 `STALE_LEASE`）；
   - `LeaseRecoveryScanner` 改为按单条任务独立小事务恢复，隔离单条异常；
   - 新增显式白名单异常 `TransientJobException`，`JobExecutor` 对其按 `attempt < max_attempt` 在单事务内退避重入 `QUEUED` + 写带 `next_attempt_at`（指数退避 + jitter）的 `outbox_events`，其余错误保持直接 `FAILED`。
3. **RabbitMQ DLQ 与毒消息路由**：在 `RabbitTopologyConfig` 声明死信交换机 `mangashelf.jobs.dlx` 与死信队列 `content.dlq` 并绑定三条任务队列；`JobConsumer` 对反序列化失败/非法载荷的毒消息执行 `basicNack(tag, false, false)` 路由进 DLQ。
4. **Outbox / Job Micrometer 指标**：在现有 `MeterRegistry` 注册 `mangashelf.outbox.*` 与 `mangashelf.jobs.*` 关键计数、耗时、滞留与租约抢占指标。
5. **Testcontainers 故障注入集成测试**：在 `server/pom.xml` 引入 `test` scope 的 Testcontainers（PostgreSQL + RabbitMQ），新增 `OutboxReliabilityIT` 与 `WorkerLeaseFencingIT` 覆盖 6 大故障注入场景，并将实测结果写入 `docs/validation/harden-reliable-jobs-and-fault-injection.md`。

## Capabilities

### New Capabilities

（无新增对外产品能力，属于既有任务流水线可靠性的加固。）

### Modified Capabilities

- `upload-pipeline`：补充 Outbox 领取租约互斥、单条恢复隔离、瞬时/永久错误白名单分类、毒消息 DLQ 隔离、动画核验租约保护与核心运维指标要求。

## Impact

- **受影响代码**：`server/pom.xml`、`server/src/main/java/com/mangashelf/ingestion/`、`server/src/main/java/com/mangashelf/animation/`、`server/src/main/java/com/mangashelf/config/RabbitTopologyConfig.java`、`server/src/test/java/com/mangashelf/ingestion/`。
- **数据库迁移**：无需新增表或破坏性改表（完全复用现有 `jobs` / `outbox_events` 列）。
- **运维注意**：已有 RabbitMQ 环境中三条任务队列若已按旧参数（无 `x-dead-letter-exchange`）声明，升级前需先清理旧空队列或通过 RabbitMQ Policy 注入 DLX 参数，避免 AMQP `PRECONDITION_FAILED`。
