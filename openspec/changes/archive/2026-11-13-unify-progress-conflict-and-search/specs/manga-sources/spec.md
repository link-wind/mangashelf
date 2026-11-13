## MODIFIED Requirements

### Requirement: 外部漫画进度服务端保存

外部漫画阅读进度 MUST 保存在服务端并可在历史中按原来源、作品、章节及页码续读。
进度写入 MUST 携带 `expectedVersion`，版本冲突时返回 409 `PROGRESS_CONFLICT` 与当前快照，
MUST NOT 覆盖已保存位置；进度列表 MUST 返回 `version` 供客户端续读时携带。

#### Scenario: 跨设备续读

- **WHEN** 用户从历史打开同一作品的同一章节
- **THEN** 阅读器按记录中的漫画源请求页图并恢复上次页码；历史页展示「第 X 话 第 N 页」

#### Scenario: 多设备过期写入

- **WHEN** 两台设备以同一 `expectedVersion` 保存同一作品进度
- **THEN** 后到者收到 409 与当前快照；阅读器以服务端版本更新本地状态并显式提示，不静默覆盖
