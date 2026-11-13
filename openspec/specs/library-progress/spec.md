# library-progress Specification

## Purpose
TBD - created by archiving change implement-manga-reading. Update Purpose after archive.

## Requirements

### Requirement: 收藏与取消收藏

登录用户 MUST 能将可见作品加入或移出收藏；同一用户对同一作品的收藏 MUST 唯一。
重复加入返回既有收藏，不重复创建。

#### Scenario: 重复加入收藏

- **WHEN** 用户对已在收藏中的作品再次发起收藏
- **THEN** 系统返回既有收藏条目，条目数量不变

### Requirement: 阅读进度以版本号防覆盖

进度写入 MUST 携带 expected_version：匹配时更新位置并递增版本；不匹配时返回
409 PROGRESS_CONFLICT 及当前快照。进度 MUST 允许向前或向后阅读，
MUST NOT 用最大页码代替真实位置。

#### Scenario: 过期客户端写入

- **WHEN** 客户端以过期 expected_version 提交进度
- **THEN** 系统返回 409 与当前快照，已保存进度不被覆盖

#### Scenario: 向回翻阅

- **WHEN** 用户从第 10 页跳回第 3 页并保存进度
- **THEN** 进度记录为第 3 页，刷新后从第 3 页继续

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
