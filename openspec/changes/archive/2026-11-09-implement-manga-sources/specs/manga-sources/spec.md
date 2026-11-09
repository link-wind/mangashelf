## ADDED Requirements

### Requirement: 漫画数据源可插拔

平台 MUST 以统一接口描述漫画数据源及其能力，新增源不改动统一入口。

#### Scenario: 能力缺失

- **WHEN** 客户端请求某源不支持的接口（如哔哩漫画的搜索）
- **THEN** 返回 409 SOURCE_CAPABILITY_UNAVAILABLE 并说明原因

### Requirement: 热门榜元数据

漫画页 MUST 展示至少一个数据源的热门榜，并标明条目是否可站内阅读。

#### Scenario: 切换榜单源

- **WHEN** 用户在漫画页切换数据源
- **THEN** 榜单按该源排序展示；不可站内阅读的条目跳转官方页面
