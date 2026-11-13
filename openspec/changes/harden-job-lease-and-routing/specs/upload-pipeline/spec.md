## ADDED Requirements

### Requirement: 业务写入受租约保护且产物上传在事务外

任务的业务写入（页面清单、解析结果、修订状态切换等）MUST 以租约令牌条件更新：租约有效
才落库，过期 attempt 的业务写入 MUST 被拒绝且不产生任何业务行。对象存储上传 MUST 在
数据库事务外完成；上传成功后 MUST 用短事务校验租约并切换有效引用。重复队列通知 MUST
通过数据库状态与租约幂等提交，不重复产生页面或版本。

#### Scenario: 过期 attempt 的页面写入被拒绝

- **WHEN** 旧 attempt 携带过期令牌写入页面清单
- **THEN** 业务写入被拒绝，页面与修订状态不改变，结果提交也被拒绝

#### Scenario: 上传后短事务切换引用

- **WHEN** 章节处理完成产物上传后提交
- **THEN** 对象已在存储中独立存在；短事务校验租约通过才切换修订引用与状态

### Requirement: 派发与恢复的并发边界

outbox 派发 MUST 使用领取租约：领取时写入令牌与期限，发送确认后按令牌更新，
超时未确认的记录可被其他实例回收重派。租约恢复 MUST 在同一事务内完成"任务置回
待派发、写入 outbox、追加恢复事件"，并在事务内判断 attempt 上限；超限任务进入
FAILED 等待显式重跑。任务事件序号 MUST 在数据库中串行分配，并发追加 MUST NOT
产生重复序号或插入失败。

#### Scenario: outbox 领取租约超时回收

- **WHEN** 某实例领取 outbox 记录后在发送确认前退出
- **THEN** 租约到期后其他实例可重新领取并派发，消息不丢失也不重复入库

#### Scenario: 恢复事务原子

- **WHEN** 租约过期触发恢复且进程在恢复中途崩溃
- **THEN** 数据库中不会出现"QUEUED 但无 outbox 记录"的孤儿任务；恢复要么整体发生要么不发生

#### Scenario: 并发追加事件

- **WHEN** 两个实例同时向同一任务追加事件
- **THEN** 序号由数据库串行分配，两次追加都成功且序号不重复

## ADDED Requirements

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
