# upload-pipeline 规格（M1 增量）

## MODIFIED Requirements

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
