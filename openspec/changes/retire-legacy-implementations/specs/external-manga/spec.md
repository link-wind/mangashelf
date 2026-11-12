## MODIFIED Requirements

### Requirement: 外部漫画搜索与阅读

平台 MUST 通过统一数据源接口（manga-sources）按源搜索外部漫画并提供站内阅读；
MangaDex 与其他源 MUST 共用同一详情、章节与页图响应协议，前端 MUST NOT 按源分叉调用不同端点。

#### Scenario: 搜索并阅读

- **WHEN** 用户在 /manga-ext 搜索并打开一部作品、选择可读章节
- **THEN** 详情、章节与页图请求统一走按源接口，阅读器按章节顺序竖向展示页面图片，支持切换省流/高清

#### Scenario: 外链章节

- **WHEN** 章节由 MangaDex 外链到官方站（无托管页图）
- **THEN** 列表不提供阅读入口，并显式提示切换语言或前往原站
