## Context

动机见 proposal.md，依据 docs/distributed-evolution.md 第 3 节与 DESIGN.md 第 11 节。当前实现事实（2026-11-13 核验）：

- `OutboxRepository.claimBatch`：`REQUIRES_NEW` + `FOR UPDATE SKIP LOCKED`，领取后行锁随事务结束释放，发送期间无租约；确认/未确认直接 UPDATE，无令牌校验。
- `LeaseRecoveryScanner.recoverExpiredLeases`：无事务，先条件 UPDATE 置回 QUEUED（逐行自动提交），循环内再写 outbox + 事件；中途崩溃留下"QUEUED 无 outbox"孤儿，且恢复扫描只扫 RUNNING，孤儿永远不派发。
- `JobRepository.appendEvent`：`INSERT ... SELECT COALESCE(MAX(sequence),0)+1`，两实例并发向同一任务追加会撞 `(job_id, sequence)` 主键（V1 主键定义）。
- `ChapterParseHandler.run`：`tx.executeWithoutResult` 内循环 `uploadPage`（含 `s3.putObject`）+ deletePages + 状态切换；`AnimationVerifyHandler` 同模式。业务写入全部不带 lease_token，仅 `commitResult`/`heartbeat` 校验。
- `JobDispatcher.dispatch`：所有消息统一发 `QUEUE_CONTENT_PROCESS`；`RabbitTopologyConfig` 已声明 `content.index` 队列且 `JobConsumer` 已监听，但无人发布。
- `JobConsumer` 同时监听两个队列、MANUAL ack、异常也 ack（恢复靠租约扫描）；API 与 Worker 共用 `JobExecutor`，无角色过滤——API 也能领浏览器解析任务并启动 Chromium。
- 队列与 kind 对应关系：解析类 `external.resolve`/`manga.plan`；处理类 `chapter.parse`/`media.verify`/`content.verify`；索引类 `index.build`。

## Goals / Non-Goals

**Goals:**

- outbox 派发与租约恢复在崩溃/并发下不丢消息、不留孤儿、不重复执行。
- 过期 attempt 的业务写入被租约拒绝；上传移出事务。
- 事件序号数据库串行分配，多实例并发追加安全。
- 队列按资源特征拆分，Worker 按配置消费；浏览器解析只在 Worker 运行。

**Non-Goals:**

- 不引入调度框架、通用工作流引擎或延迟队列插件。
- 不改 Python 任务协议与任务入口。
- 不做 RabbitMQ publisher confirm 以外的投递保证升级（quorum queue 属基础设施高可用阶段）。
- 不改前端。

## Decisions

1. **outbox 领取租约：加两列，单 UPDATE 领取。**
   V25 迁移为 `outbox_events` 加 `lease_token uuid NULL`、`lease_until timestamptz NULL`。
   `claimBatch` 改为一条条件 UPDATE：对候选行（PENDING 且到期，或已领取但 `lease_until < now()`）
   写入新令牌与期限（30s，覆盖 confirm 超时 10s + 余量），`RETURNING` 领取行。
   确认后 `markDispatched(token)`：`WHERE id = ? AND lease_token = ?`。超时回收复用同一查询，
   无需独立扫描器。备选：进程内存领取集合——崩溃即丢，租约列才是数据库事实。

2. **恢复事务化：单方法 `@Transactional` 包住三步。**
   `recoverExpiredLeases` 改为事务方法：`UPDATE jobs ... RETURNING`（含 attempt 上限判断——
   `CASE WHEN attempts >= max THEN FAILED ELSE QUEUED END`），对 QUEUED 行循环写 outbox 与恢复
   事件，全部在同一事务提交；FAILED 行只写终态事件。崩溃时整体回滚，无孤儿。
   备选：继续两步——已知孤儿路径，必须修复。

3. **事件序号：专用序列表 + 单语句分配。**
   V25 建 `job_event_sequences (job_id uuid PRIMARY KEY, next_seq bigint NOT NULL)`。
   `appendEvent` 改为单语句：`INSERT INTO job_event_sequences VALUES (?,1) ON CONFLICT DO UPDATE
   SET next_seq = job_event_sequences.next_seq + 1 RETURNING next_seq`，拿序号后再插事件行；
   两条语句包在事务内（appendEvent 调用点已大多在事务中，`JobExecutor` 的回调行改为包装小事务）。
   备选：PG 序列按 job 建对象——对象数不可控；advisory lock——需要应用侧管理锁生命周期。

4. **业务写入租约校验：`ChapterParseHandler` 加令牌条件更新。**
   `catalog.deletePages/setRevisionPageCount/markRevisionStatus` 增加 `WHERE lease_token = ?`
   版本（仓储层新增带令牌的重载）；handler 上传完成后的短事务内先条件更新，0 行即抛
   `StaleLeaseException` → 结果按失败提交（会被 commitResult 拒绝，业务行已保证未写入）。
   上传循环移出事务：先上传全部产物（对象独立存在，attempt 目录隔离），再短事务切换引用。
   `AnimationVerifyHandler` 同模式处理。备选：DB 层事务回滚兜底——回滚后对象已上传但引用
   不落库，仍需条件更新保证"谁的有效"。

5. **队列路由：`kind → routing key` 静态映射。**
   `JobDispatcher` 按消息中的 kind（payload 无 kind，改由 outbox 行 join jobs 取 kind 或在
   payload 构造时写入 kind 字段——选后者，`JobMessage` 加 `kind`，构造点已集中）映射路由键；
   未知 kind 落 `content.process` 并告警。拓扑新增 `content.resolve` 队列声明与绑定。
   备选：topic 交换机通配——现有 direct 交换机够用，不换类型。

6. **Worker 消费范围与角色过滤。**
   `application-worker.yml` 增加 `mangashelf.jobs.consume-queues: [content.resolve,
   content.process]`（index 默认关闭）；`JobConsumer` 用配置集合注册 `@RabbitListener`
   （编程式容器或按配置生成监听器）。`JobExecutor` 增加角色过滤：API 领取时跳过解析类 kind
   （claim SQL `AND kind NOT IN ('external.resolve','manga.plan')`，API 角色）；
   解析类队列仅 Worker 消费，故 API 实际领到的只有被误投的处理类消息。
   浏览器型数据源在 API 上返回明确的"解析任务等待执行"提示文案改造为
   `SOURCE_BUSY`（resolve 轮询超时已有错误码，沿用）。

7. **重复通知幂等维持现状语义。**
   `JobConsumer` 已在重复通知时靠 `tryClaim` 失败仅 ACK；本次不改消费语义，只补路由与队列声明。

## Risks / Trade-offs

- [领取租约过期误回收在途消息] → 期限 30s > confirm 超时 10s；回收重派靠幂等提交兜底（重复通知仅 ACK）。
- [attempt 上限判断移入恢复事务改变既有行为] → 既有 `maxAttempts` 配置沿用；超限进 FAILED 是显式失败（AGENTS.md 显式报错原则）。
- [序列表增加一次往返] → appendEvent 频率低（阶段级），可接受；hot path 只有 progress 事件。
- [业务写入带令牌改造漏点] → 以 `ChapterParseHandler`/`AnimationVerifyHandler` 清单为准逐处改；验证场景含过期 attempt 写入拒绝。
- [队列拆分后消息错投] → kind 静态映射单点维护 + 未知 kind 告警；真实 RabbitMQ 验证三类任务各落其队列。
- [API 角色过滤漏网] → 解析类队列本身只有 Worker 声明消费，API 误领仅在直接 claim 路径，SQL 过滤兜底。

## Migration Plan

1. V25 迁移（outbox 两列 + 序列表）。
2. 单机 compose 一次性发布（API+Worker 同镜像）；队列声明自动创建。
3. 验证重复通知、租约恢复、三类队列路由、API 不领解析任务。
4. 回滚：代码回滚后 outbox 新列与序列表保留（旧代码忽略）；已拆分队列无害。

## Open Questions

无。payload 加 kind 字段（决策 5）影响 outbox 存量行——派发时 join jobs 补取 kind，不迁移存量 payload。
