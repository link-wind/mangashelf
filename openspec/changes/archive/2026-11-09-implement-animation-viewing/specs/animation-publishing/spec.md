# animation-publishing 规格（M2）

## ADDED Requirements

### Requirement: 下架停止对外展示

所有者 MUST 能下架已发布动画；下架后新播放与关联查询 MUST 不再返回该动画，
已签发地址仍受短有效期边界约束（DESIGN 3.3）。

#### Scenario: 下架后请求播放

- **WHEN** 动画被下架后读者请求播放
- **THEN** 返回 404；签名未过期的旧地址在其有效期内仍可访问（明确边界）

### Requirement: 观看进度版本号防覆盖且绑定版本

观看进度写入 MUST 携带 expected_version，不匹配返回 409 与当前快照；
进度行 MUST 记录其绑定版本；当前版本变化时 MUST NOT 把旧秒数套用到新时长。

#### Scenario: 切换版本后的续播

- **WHEN** 动画发布新版本后读者请求播放
- **THEN** 播放信息标明进度绑定的是旧版本，播放器提示从头播放
