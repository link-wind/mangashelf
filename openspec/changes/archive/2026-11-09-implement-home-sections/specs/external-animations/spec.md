## ADDED Requirements

### Requirement: 首页内容分区

首页 MUST 展示 Banner 轮播、新番时刻表预览、最近更新、本季高分榜与分类入口，
数据均来自平台已有接口。

#### Scenario: Banner 轮播切换

- **WHEN** 用户查看首页
- **THEN** 首屏展示本季评分前 4 的轮播 Banner，可点击缩略图切换，含播放/追番入口

#### Scenario: 最近更新

- **WHEN** 用户查看首页
- **THEN** 「最近更新」按资源更新时间倒序展示目录条目及更新进度
