## MODIFIED Requirements

### Requirement: 浏览器解析的漫画源

平台 MUST 支持通过无头浏览器执行源站 JS 获得章节页图，用于纯 HTTP 无法解析的源。
浏览器解析任务 MUST 由 Worker 角色执行，API 角色 MUST NOT 运行 Chromium；
无 Worker 消费解析队列时 MUST 显式提示解析不可用，其他数据源与首页基础功能
MUST NOT 受影响。公开拷贝漫画章节 MUST NOT 把浏览器作为正常阅读路径；只有章节
明确要求登录且已配置登录态时，才允许回退浏览器。

#### Scenario: 站内阅读纯浏览器源

- **WHEN** 用户打开只能由浏览器解析的源章节
- **THEN** Worker 执行 Playwright 解析出页图 URL，阅读器可按需加载，API 进程内无浏览器实例

#### Scenario: 站内阅读公开拷贝漫画章节

- **WHEN** 用户从拷贝漫画详情页打开公开章节
- **THEN** 服务端通过 v3 HTTP 接口返回完整页图，不启动浏览器

#### Scenario: 需要登录的章节

- **WHEN** 章节内容仅登录可见且已配置登录态
- **THEN** 允许浏览器回退；未配置登录态时返回明确错误，不展示空阅读器

#### Scenario: 只允许站内 URL

- **WHEN** 解析请求携带非 copy4000.com 的 URL
- **THEN** Python 任务拒绝执行

#### Scenario: 纯 API 部署下的浏览器源

- **WHEN** 仅部署 API 而无 Worker 消费解析队列时访问浏览器型数据源
- **THEN** 该源明确返回解析不可用，漫画首页与 HTTP 数据源仍正常
