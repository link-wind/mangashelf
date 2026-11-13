## ADDED Requirements

### Requirement: 进度写入统一版本冲突语义

所有进度写入（本地阅读、本地观看、外部漫画、外部观看）MUST 携带 `expectedVersion`
并采用同一冲突语义：版本匹配时更新位置并递增版本，返回新快照；不匹配时 MUST 返回
409 `PROGRESS_CONFLICT`，`details.current` 为服务端当前快照，且 MUST NOT 覆盖已保存位置。
首次写入 MUST 以 `expectedVersion = 0` 表示。批量列表响应 MUST 返回每条的 `version`，
供客户端续读时携带。

#### Scenario: 外部漫画过期写入

- **WHEN** 两台设备分别以同一 `expectedVersion` 保存同一外部漫画进度
- **THEN** 先到者成功并递增版本，后到者收到 409 与当前快照，已保存进度不被覆盖

#### Scenario: 外部观看过期写入

- **WHEN** 旧请求携带过期 `expectedVersion` 保存外部集观看进度
- **THEN** 返回 409 与当前快照，播放器采纳服务端位置

#### Scenario: 首次写入

- **WHEN** 用户首次保存某作品进度并携带 `expectedVersion = 0`
- **THEN** 创建进度记录，版本为 1

#### Scenario: 缺少版本

- **WHEN** 客户端未携带 `expectedVersion`
- **THEN** 返回 400，不写入进度
