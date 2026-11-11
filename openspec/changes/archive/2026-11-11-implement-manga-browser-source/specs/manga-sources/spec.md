# manga-sources 增量：漫画浏览器解析源

## ADDED Requirements

### Requirement: 浏览器解析的漫画源

平台 MUST 支持通过无头浏览器执行源站 JS 获得章节页图，用于纯 HTTP 无法
解析的源（如拷贝漫画）。

#### Scenario: 站内阅读拷贝漫画

- **WHEN** 用户从拷贝漫画详情页打开章节
- **THEN** 服务端用 Playwright 解析出页图 URL，阅读器可直接翻页阅读

#### Scenario: 需要登录的章节

- **WHEN** 章节内容仅登录可见（游客 contentKey 为空）
- **THEN** 返回明确错误提示配置 Cookie/登录态，不展示空阅读器

#### Scenario: 只允许站内 URL

- **WHEN** 解析请求携带非 copy4000.com 的 URL
- **THEN** Python 任务拒绝执行
