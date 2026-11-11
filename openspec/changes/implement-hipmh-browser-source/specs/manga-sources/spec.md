# manga-sources 增量：分页章节 API

## ADDED Requirements

### Requirement: 浏览器源支持分页章节接口

浏览器规则源 MUST 支持通过公开分页 API 获取章节列表。

#### Scenario: 自动翻页

- **WHEN** 规则配置 `chaptersApi` 且接口返回 `total_pages > 1`
- **THEN** 服务端按规定页大小逐页拉取并合并为完整章节列表

#### Scenario: 章节标识通用化

- **WHEN** 章节接口返回站点自有 ID（如 hid）
- **THEN** 阅读页直接以该 ID 构造 URL，不再强制要求 `{mangaId}|{slot}`
