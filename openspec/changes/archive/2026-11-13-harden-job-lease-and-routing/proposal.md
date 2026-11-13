## Why

docs/distributed-evolution.md 第 3 节：现有 `jobs + outbox + job_events + lease_token` 方向正确，但五个边界在多实例下会出错——outbox 读取与派发无领取租约、租约恢复两步非同一事务、业务写入不受租约保护、章节处理事务内包含对象上传、事件序号 `MAX(sequence)+1` 并发撞主键。同时所有任务统一发往 `content.process` 单队列，浏览器解析与文件处理无法按资源特征隔离扩容。多实例部署（变更序列 4）之前必须先补齐这些边界。

## What Changes

- outbox 派发加领取租约：`claimBatch` 领取时写入 `lease_token/lease_until`，发送结束后按令牌更新，超时未确认的记录可被回收重派；数据库任务记录仍是事实，队列只通知。
- 租约恢复事务化：`claimExpiredLeases` 置回 QUEUED、写 outbox、追加恢复事件在同一事务内提交；attempt 上限在事务内判断，超限进入 FAILED 等待显式重跑。
- 事件序号数据库串行分配：新迁移引入 `job_event_seq(job_id, sequence)` 序列语义（用 advisory lock 或专用序列表），`appendEvent` 不再用 `MAX(sequence)+1`。
- 业务写入受租约保护：`ChapterParseHandler` 的页面删除/写入、修订状态切换等以租约令牌条件更新；过期 attempt 的业务写入被拒绝且不落库。
- 上传移出事务：章节处理先上传 attempt 独立产物到对象存储，再用短事务校验租约并切换有效引用；`DeriveJobHandler` 已退役，此项只涉及 `ChapterParseHandler` 与 `AnimationVerifyHandler`（同类模式）。
- 队列拆分：派发按 job kind 路由到 `content.resolve`（external.resolve、manga.plan 等）/`content.process`（chapter.parse、media.verify）/`content.index`（index.build）；Worker 按配置声明消费队列，`content.index` 消费默认关闭。
- 浏览器解析移入 Worker：API 角色不再运行浏览器解析任务（`JobExecutor` 按 kind 与角色过滤领取），`external.resolve`/`manga.plan` 全部由 Worker 执行。
- **BREAKING**：`/api/v1/external-manga/captured/...`、`/api/v1/manga-sources/capture/...` 410 桩保留；Python `manga.plan` 任务入口不变，仅运行位置变化。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `upload-pipeline`：任务通知与结果提交的既有要求保持；新增"业务写入受租约保护、产物上传在事务外"要求。
- `external-animations`：解析任务由 Worker 执行（API 不再运行浏览器），播放链路行为不变。
- `manga-sources`：浏览器解析源由 Worker 执行；浏览器型数据源在纯 API 部署下显式报"解析不可用"而非挂起。

## Impact

- 后端：`ingestion`（OutboxRepository、JobDispatcher、LeaseRecoveryScanner、JobRepository、JobExecutor、ChapterParseHandler、AnimationVerifyHandler、RabbitTopologyConfig、JobConsumer）、`application-worker.yml`
- 数据库：V25 迁移（job_events 序列分配结构）
- 无前端改动；无新依赖
- 风险：任务提交路径改动需在真实 RabbitMQ 验证重复通知与恢复场景
