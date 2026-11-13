# harden-job-lease-and-routing 验证记录

- 日期：2026-11-13
- 基线：`main` `21ec22c` + 本变更工作区改动
- 环境：本地 jar（API 18081）+ compose worker 容器 + 真实 PostgreSQL / RabbitMQ（三队列）

## 验证场景与结果

| 场景 | 结果 |
| --- | --- |
| V25 迁移 | outbox 加 `lease_token/lease_until`、`job_event_sequences` 建表成功（v25 applied） |
| 队列拓扑 | `content.resolve`/`content.process`/`content.index` 三队列 + direct 绑定自动声明 |
| 事件序号并发分配 | 2 并发 × 20 次分配同一任务序号：`next_seq` 严格递增到 41，无碰撞无插入失败（旧实现 `MAX+1` 会撞主键） |
| 按类型路由 | 新格式 payload（含 kind）的 `external.resolve` 派发到 **content.resolve**；旧格式遗留 payload（无 kind）落 process 兜底（未知 kind 告警路径） |
| outbox 领取租约 | 领取写令牌+期限；确认按令牌更新；制造"领取后崩溃"（PENDING+过期租约）→ 12s 内被重新领取并重派，消息到达队列 |
| 恢复事务化 | 制造 RUNNING+过期租约：恢复后同一事务内 `QUEUED + dispatch 2 outbox + 恢复事件` 三者原子出现，无孤儿 QUEUED |
| Worker 消费解析队列 | worker 容器消费 content.resolve，3 个解析任务全部由 Worker 执行（2 SUCCEEDED + 1 上游无视频流 FAILED，与改造无关） |
| API 不执行浏览器解析 | API 进程日志 0 次 `claimed kind=external.resolve`、0 次 chromium/playwright 引用（SQL 过滤兜底 + 队列只有 Worker 声明消费） |
| 重复通知幂等 | 同一消息投递两次：第一次领取执行；第二次（终态/有效租约）直接 ACK，attempt 不变 |
| 业务写入租约保护 | `ChapterParseHandler`：上传移出事务，短事务内 `requireLeasedRevision` 校验后切换引用；`STALE_LEASE` 失败路径就位（真实过期场景经恢复+幂等组合覆盖） |
| 重复任务落库幂等 | 恢复重派产生的 dispatch 2 与已有 dispatch 1 并存，`(job_id, dispatch_no)` 唯一约束生效 |
| 后端测试 | 43/43 通过 |
| Python 测试 | 通过（任务协议未变） |

## 实施发现

- **`claimBatch` RETURNING 漏列**：初版 `RETURNING` 未包含 `lease_token`，DataClassRowMapper
  报"ResultSet 中找不到栏位名称 lease_token"，派发器持续失败直到修复。教训：改 record 字段
  必须同步改 RETURNING 列清单。
- **payload 无 kind 的存量消息**：API 重启前遗留的 DISPATCHED 记录重试时读不到 kind，
  `JobMessage.kind` 为 null → 落 process 队列兜底（符合 design 决策 5 的降级路径）。

## 已知限制

- `AnimationVerifyHandler` 的同类改造（上传出事务 + 租约校验）已在 tasks 列出但本次未执行
  （无真实动画核验样本；模式与 ChapterParseHandler 相同，follow-up 处理）。
- `content.index` 队列已声明但 consume-queues 默认不含它（index.build 现无产生方）。
- Worker 镜像以宿主 jar 组装验证（构建期 Maven 网络限制，同前变更）；服务器构建不受影响。
- 过期 attempt 写入拒绝（STALE_LEASE）通过代码路径与编译验证，未构造真实竞态（需要
  精确的租约过期时序；恢复+幂等组合已覆盖等价语义）。
