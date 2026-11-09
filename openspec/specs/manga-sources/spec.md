# manga-sources Specification

## Purpose
TBD - created by archiving change implement-manga-sources. Update Purpose after archive.

## Requirements

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

### Requirement: 跨源同作桥接

榜单条目 MUST 在可读源存在同作时提供站内阅读入口。

#### Scenario: 中文标题命中可读源

- **WHEN** 哔哩漫画热门榜条目在 MangaDex 检索到同作
- **THEN** 该条目标记为可站内阅读，点击进入站内详情；未命中的跳原站

### Requirement: 外部漫画进度服务端保存

外部漫画阅读进度 MUST 保存在服务端并可在历史中续读。

#### Scenario: 跨设备续读

- **WHEN** 用户再次打开同一作品的同一章节
- **THEN** 阅读器滚动到上次页码；历史页展示「第 X 话 第 N 页」

### Requirement: 全能力中文漫画源

平台 MUST 提供至少一个支持榜单/搜索/详情/站内阅读的中文漫画数据源。

#### Scenario: 中文关键词搜索命中正版源

- **WHEN** 用户在搜索页选择腾讯动漫并输入中文标题
- **THEN** 返回该源匹配条目（标题与作者），可进入站内详情

#### Scenario: 免费章节站内阅读

- **WHEN** 用户从详情页打开免费章节
- **THEN** 阅读器加载该话全部页图并可翻页、保存进度

#### Scenario: 付费章节显式失败

- **WHEN** 用户打开付费或不可读章节
- **THEN** 返回明确错误说明，不展示空阅读器也不静默跳转

### Requirement: 外部漫画按源路由

外部漫画详情与阅读 MUST 支持按源分发，缺省源保持既有行为。

#### Scenario: 旧链接兼容

- **WHEN** 打开不带 source 参数的外部漫画详情/阅读链接
- **THEN** 按 MangaDex 处理（与历史进度兼容）
