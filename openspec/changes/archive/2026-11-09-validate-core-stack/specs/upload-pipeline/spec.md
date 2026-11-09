# upload-pipeline 规格（M0 增量）

## ADDED Requirements

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

Java Worker MUST 通过固定入口 `python -m processing.run --task <task.json>` 调用处理任务；
任务目录外路径 MUST 被拒绝。Python 进程的标准输出 MUST 仅包含 JSONL 进度事件，
日志写入标准错误，完成后写出 `result.json`（含相对路径、页序、尺寸、SHA-256）。
Java MUST 在校验产物路径位于任务目录内、文件类型与内容摘要一致后才上传产物。

#### Scenario: 处理任务产出可核验产物

- **WHEN** Worker 以合法 task.json 调用处理任务并等待结束
- **THEN** 产物文件经摘要核对后上传对象存储，任务结果提交成功

#### Scenario: 产物路径越界

- **WHEN** result.json 声明的产物路径位于任务目录之外
- **THEN** Java 拒绝上传该产物并将任务标记为失败

### Requirement: 任务通知可重复且结果幂等

系统 MUST 在数据库事务中同时写入任务与待派发记录；派发 MUST 以 broker 发布确认作为
已派发依据。重复消息 MUST NOT 导致重复执行：已成功、已取消或持有有效租约的任务收到
重复通知时仅确认消息。Worker 提交结果 MUST 同时匹配任务状态与租约令牌。

#### Scenario: 同一消息投递两次

- **WHEN** 同一派发消息被投递两次且任务已成功
- **THEN** 第二次消费仅确认消息，不产生新的执行记录或产物

#### Scenario: 过期 Worker 提交结果

- **WHEN** 租约已过期并被新 attempt 取代的旧 Worker 提交结果
- **THEN** 提交被拒绝，任务保持新 attempt 的执行路径
