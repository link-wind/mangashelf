# library-progress Specification

## Purpose
TBD - created by archiving change implement-manga-reading. Update Purpose after archive.

## Requirements

### Requirement: 收藏与取消收藏

登录用户 MUST 能将可见作品加入或移出书架；同一用户对同一作品的收藏 MUST 唯一。
重复加入返回既有收藏，不重复创建。

#### Scenario: 重复加入书架

- **WHEN** 用户对已在书架中的作品再次发起收藏
- **THEN** 系统返回既有书架条目，条目数量不变

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
