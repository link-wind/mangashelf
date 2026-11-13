## MODIFIED Requirements

### Requirement: 外部集站内播放（解析直链 + 浏览器直连）

外部集播放 MUST 由服务端解析直链并交浏览器直连来源；解析任务 MUST 由 Worker 执行，
API 角色 MUST NOT 运行浏览器。解析幂等、失败显式降级与 token 过期续播行为保持不变；
无 Worker 消费解析队列时 MUST 显式提示解析不可用。

#### Scenario: 解析并播放外部集

- **WHEN** 用户点播一部已绑定外部集
- **THEN** 服务端创建解析任务由 Worker 执行，完成后返回直链由浏览器直连播放

#### Scenario: 解析失败显式降级

- **WHEN** 解析失败（源站无直链或超时）
- **THEN** 返回明确错误与可重试入口，不伪造播放地址

#### Scenario: 解析幂等

- **WHEN** 对同一集重复发起解析（幂等窗口内）
- **THEN** 复用同一任务与结果，不重复排队

#### Scenario: token 过期续播

- **WHEN** 直链 token 过期
- **THEN** 重新解析可恢复播放，进度保留

#### Scenario: 无 Worker 时解析不可用

- **WHEN** 无 Worker 消费解析队列时发起解析
- **THEN** 任务保持待执行并提示解析不可用，不假装成功
