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
