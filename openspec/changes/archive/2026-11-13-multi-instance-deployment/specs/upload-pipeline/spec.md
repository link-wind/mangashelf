## MODIFIED Requirements

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
