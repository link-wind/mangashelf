## MODIFIED Requirements

### Requirement: 外部集播放进度由服务端保存

外部集的观看进度 MUST 保存在服务端并跨设备续播；切换线路 MUST NOT 丢失进度。
进度写入 MUST 携带 `expectedVersion`，版本冲突时返回 409 `PROGRESS_CONFLICT` 与当前快照，
MUST NOT 覆盖已保存位置；历史与续看列表 MUST 返回 `version` 供播放器携带，
并 MUST 支持按作品与集号读取单条进度。

#### Scenario: 跨设备续播

- **WHEN** 用户在某设备观看到 position，之后在另一设备打开同一作品同一集
- **THEN** 播放器从该 position 续播

#### Scenario: 切换线路保持进度

- **WHEN** 用户在某线路看到 position 后切换到另一线路的同集号
- **THEN** 播放器仍从该 position 续播

#### Scenario: 过期写入不覆盖

- **WHEN** 旧请求携带过期 `expectedVersion` 保存观看进度
- **THEN** 返回 409 与当前快照；播放器以服务端版本更新本地状态并显式提示，不静默覆盖
