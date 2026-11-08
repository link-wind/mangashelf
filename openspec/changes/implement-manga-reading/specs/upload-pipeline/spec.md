# upload-pipeline 规格（M1 增量）

## MODIFIED Requirements

### Requirement: 处理任务通过固定协议执行

（M1 修订）处理任务 MUST 通过固定入口 `python -m processing.run --task <task.json>` 调用；
任务目录外路径 MUST 被拒绝。`content.verify` 完成后 MUST NOT 自动派生任何任务；
以章节版本为目标的解析（`chapter.parse`）MUST 由所有者显式创建修订触发。
CBZ 解析 MUST 拒绝超出条目数、单文件与总量上限、包含目录穿越、符号链接或嵌套压缩包的输入，
并保留自然排序结果供所有者确认。

#### Scenario: CBZ 包含越界或超限条目

- **WHEN** 上传的 CBZ 含目录穿越路径、条目数或解压总量超限
- **THEN** 解析任务失败并给出可定位的错误码，版本进入 FAILED，不产生页面

#### Scenario: 验证完成不再自动派生

- **WHEN** 上传通过 content.verify
- **THEN** 不创建任何后续任务；创建章节版本由所有者显式操作触发
