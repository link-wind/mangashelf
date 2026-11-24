# upload-pipeline Specification

## Purpose
TBD - created by archiving change validate-core-stack. Update Purpose after archive.

## Requirements

### Requirement: 上传会话由服务端生成对象位置

系统 MUST 在创建上传会话时由服务端生成对象 Key，并通过短期预签名 PUT 地址授权写入；
用户 MUST NOT 能够指定任意桶或对象 Key。

#### Scenario: 创建上传会话

- **WHEN** 客户端提交用途、声明大小与摘要创建上传
- **THEN** 服务端返回上传 ID、对象 Key 与短期预签名 PUT 地址及必须携带的请求头

#### Scenario: 使用过期或被篡改的签名

- **WHEN** 客户端使用过期或被修改的预签名地址上传
- **THEN** 对象存储拒绝写入，上传会话保持未核验状态

### Requirement: 上传核验完成前不可绑定

系统 MUST 在对象经核验（存在性、大小，以及无可信校验信息时的流式 SHA-256）之后才将资产
标记为 VERIFIED；重复调用完成接口 MUST 返回同一结果，MUST NOT 重复创建资产或任务。

#### Scenario: 重复调用完成接口

- **WHEN** 同一上传的完成接口被调用两次
- **THEN** 两次返回同一资产与任务结果，资产数量不变

### Requirement: 处理任务通过固定协议执行

（M1 修订）处理任务 MUST 通过固定入口 `python -m processing.run --task <task.json>` 调用；
任务目录外路径 MUST 被拒绝。Python 进程的标准输出 MUST 仅包含 JSONL 进度事件，
日志写入标准错误，完成后写出 `result.json`（含相对路径、页序、尺寸、SHA-256）。
Java MUST 在校验产物路径位于任务目录内、文件类型与内容摘要一致后才上传产物。
`content.verify` 完成后 MUST NOT 自动派生任何任务；
以章节版本为目标的解析（`chapter.parse`）MUST 由所有者显式创建修订触发。
CBZ 解析 MUST 拒绝超出条目数、单文件与总量上限、包含目录穿越、符号链接或嵌套压缩包的输入，
并保留自然排序结果供所有者确认。

#### Scenario: 处理任务产出可核验产物

- **WHEN** Worker 以合法 task.json 调用处理任务并等待结束
- **THEN** 产物文件经摘要核对后上传对象存储，任务结果提交成功

#### Scenario: 产物路径越界

- **WHEN** result.json 声明的产物路径位于任务目录之外
- **THEN** Java 拒绝上传该产物并将任务标记为失败

#### Scenario: CBZ 包含越界或超限条目

- **WHEN** 上传的 CBZ 含目录穿越路径、条目数或解压总量超限
- **THEN** 解析任务失败并给出可定位的错误码，版本进入 FAILED，不产生页面

#### Scenario: 验证完成不再自动派生

- **WHEN** 上传通过 content.verify
- **THEN** 不创建任何后续任务；创建章节版本由所有者显式操作触发

### Requirement: 任务通知可重复且结果幂等

系统 MUST 在数据库事务中同时写入任务与待派发记录；派发 MUST 以 broker 发布确认作为
已派发依据。重复消息 MUST NOT 导致重复执行：已成功、已取消或持有有效租约的任务收到
重复通知时仅确认消息。Worker 提交结果 MUST 同时匹配任务状态与租约令牌。
多实例下 outbox 派发 MUST 使用领取租约（超时可回收重派），租约恢复 MUST 在单一事务内
完成状态置回、outbox 写入与恢复事件，业务写入 MUST 受租约令牌保护。

#### Scenario: 同一消息投递两次

- **WHEN** 同一派发消息被投递两次且任务已成功
- **THEN** 第二次消费仅确认消息，不产生新的执行记录或产物

#### Scenario: 过期 Worker 提交结果

- **WHEN** 租约已过期并被新 attempt 取代的旧 Worker 提交结果
- **THEN** 提交被拒绝，任务保持新 attempt 的执行路径

#### Scenario: 双 Worker 并发领取

- **WHEN** 两个 Worker 实例同时轮询领取任务
- **THEN** 同一任务只被一个实例领取执行（租约条件更新），另一实例收到重复通知仅确认

### Requirement: 按任务类型路由与 Worker 消费范围

派发 MUST 按 job kind 路由：浏览器解析类（external.resolve、manga.plan）到
`content.resolve`，文件处理类（chapter.parse、media.verify 等）到 `content.process`，
索引类（index.build）到 `content.index`。Worker MUST 按配置声明消费队列；
API 角色 MUST NOT 执行浏览器解析任务。浏览器型数据源在无 Worker 消费解析队列时
MUST 显式提示解析不可用。

#### Scenario: 解析任务进入独立队列

- **WHEN** 提交一个 external.resolve 任务
- **THEN** 消息路由到 content.resolve 队列，由配置了该队列的 Worker 领取

#### Scenario: API 不执行浏览器解析

- **WHEN** API 角色的执行器轮询任务
- **THEN** 跳过浏览器解析类 kind，任务留在队列中等待 Worker

#### Scenario: 浏览器源在纯 API 部署下显式失败

- **WHEN** 部署仅含 API 而无 Worker 消费解析队列时，用户打开浏览器型数据源章节
- **THEN** 返回明确的"解析不可用"错误，不无限等待

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
