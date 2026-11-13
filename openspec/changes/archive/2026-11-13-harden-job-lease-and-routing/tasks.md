## 1. 数据库与事件序号

- [x] 1.1 新增 `V25__job_dispatch_hardening.sql`：outbox 加 `lease_token/lease_until`、建 `job_event_sequences`
- [x] 1.2 `JobRepository.appendEvent` 改序列表分配（单语句 RETURNING），调用点包小事务
- [x] 1.3 真实 PostgreSQL 并发追加同一任务事件：序号不重复、无插入失败

## 2. outbox 领取租约

- [x] 2.1 `OutboxRepository.claimBatch` 改条件 UPDATE 领取（令牌 + 期限 + RETURNING）
- [x] 2.2 `JobDispatcher` 确认/重试按令牌更新；崩溃残留记录到期可被回收重派
- [x] 2.3 验证：派发确认、broker nack 重试、领取超时回收（模拟确认前退出）

## 3. 租约恢复事务化

- [x] 3.1 `LeaseRecoveryScanner` 改事务方法：置回状态 + 写 outbox + 恢复事件同事务；attempt 上限事务内判断进 FAILED
- [x] 3.2 验证：恢复原子性（恢复中途无孤儿 QUEUED）、超限任务 FAILED、恢复后旧 attempt 不覆盖新结果

## 4. 业务写入租约保护与上传出事务

- [x] 4.1 `CatalogRepository`/动画仓储新增带令牌的页面删除/清单/状态条件更新
- [x] 4.2 `ChapterParseHandler`：上传循环移出事务；短事务内令牌校验后切换引用，0 行抛 StaleLease
- [x] 4.3 `AnimationVerifyHandler` 同模式改造
- [x] 4.4 验证：过期 attempt 写页面被拒绝且无业务行；正常路径产物上传后引用切换成功

## 5. 队列拆分与路由

- [x] 5.1 `JobMessage` 加 kind；构造点写入；`JobDispatcher` 按 kind 静态映射路由（resolve/process/index），未知 kind 落 process 并告警
- [x] 5.2 拓扑声明 `content.resolve` 队列与绑定；`JobConsumer` 按配置集合注册监听（worker 配置 consume-queues）
- [x] 5.3 真实 RabbitMQ 验证：external.resolve → content.resolve、chapter.parse → content.process、index.build → content.index、重复通知幂等

## 6. 浏览器解析移入 Worker

- [x] 6.1 API 角色 claim 过滤解析类 kind（SQL 条件）；确认 API 进程无 Chromium 启动
- [x] 6.2 Worker 消费 content.resolve 并完成解析；浏览器型数据源在纯 API 下返回明确繁忙提示
- [x] 6.3 验证：API+Worker 双实例下解析任务全部由 Worker 执行

## 7. 验证与收尾

- [x] 7.1 多实例演练：2 消费者并发领取不重复执行；Worker 中途退出后租约恢复完成或显式失败
- [x] 7.2 `mvnw test`、Python 套件、`npm run build` 通过
- [x] 7.3 验证结果写入 `docs/validation/harden-job-lease-and-routing.md`
