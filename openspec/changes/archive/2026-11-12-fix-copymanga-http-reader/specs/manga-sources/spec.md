## ADDED Requirements

### Requirement: 拷贝漫画 HTTP 章节图片

平台 MUST 在不启动浏览器的情况下通过拷贝漫画官方 v3 匿名章节接口读取公开章节的
完整页图清单，保持上游页序。平台 MUST 使用 `chapter.size` 校验
`chapter.contents` 数量；声明页数与实际图片数不一致时 MUST 返回明确错误，不得
把不完整章节标为成功或写入成功缓存。成功清单 MUST 有明确的缓存过期与容量边界。
受限章节 MUST 返回登录提示，不伪造页图。

#### Scenario: 打开公开章节

- **WHEN** 用户打开匿名可读的拷贝漫画章节
- **THEN** 通过 v3 章节接口返回 `size` 张页图，不启动 Python 或 Chromium

#### Scenario: 声明页数与实际内容不一致

- **WHEN** 章节声明 `size=10` 但接口只返回 1 个或 9 个 `contents`
- **THEN** 返回 502 `COPYMANGA_READER_INVALID`，阅读器不显示不完整章节

#### Scenario: 上游限流或受限章节

- **WHEN** 上游返回 `code=210`
- **THEN** 返回 502 `COPYMANGA_READER_RATE_LIMITED`，不把限流表述为登录失败
- **WHEN** 章节 `is_lock=true` 且没有可用页图
- **THEN** 返回 403 `COPYMANGA_LOGIN_REQUIRED`，提示配置登录态

## MODIFIED Requirements

### Requirement: 拷贝漫画 HTTP 章节目录

平台 MUST 在不启动浏览器的情况下通过拷贝漫画官方 v3 匿名 API 读取公开详情和完整
章节目录，保持既有章节标识与官网顺序。平台 MUST 复用有效的有界目录缓存；目录
无效、缺失或上游失败时明确报错，不自动回退浏览器或返回不完整列表。目录读取 MUST
不依赖个人登录态。

#### Scenario: 首次打开详情

- **WHEN** 用户打开尚未缓存的拷贝漫画作品
- **THEN** 通过 v3 API 获取详情与完整目录，按分组和分页读取全部章节，不启动 Python 或浏览器

#### Scenario: 重复与并发打开详情

- **WHEN** 多个请求同时读取同一作品或读取有效缓存
- **THEN** 复用同一份成功结果，避免重复抓取；缓存有明确过期与容量边界

#### Scenario: 目录不完整或接口变化

- **WHEN** 目录声明数量与实际章节不符、分页截断、章节重复、UUID 非法或上游不可用
- **THEN** 返回明确错误，不把部分目录标为成功，不启动浏览器兜底

#### Scenario: 原有章节仍可打开

- **WHEN** 用户从 v3 目录打开章节或从历史记录继续阅读
- **THEN** 使用既有章节 ID 和 v3 章节图片接口，阅读器按需加载页图

### Requirement: 浏览器解析的漫画源

平台 MUST 支持通过无头浏览器执行源站 JS 获得章节页图，用于纯 HTTP 无法解析的源。
公开拷贝漫画章节 MUST NOT 把浏览器作为正常阅读路径；只有章节明确要求登录且已配置
登录态时，才允许回退浏览器。

#### Scenario: 站内阅读纯浏览器源

- **WHEN** 用户打开只能由浏览器解析的源章节
- **THEN** 服务端用 Playwright 解析出页图 URL，阅读器可按需加载

#### Scenario: 站内阅读公开拷贝漫画章节

- **WHEN** 用户从拷贝漫画详情页打开公开章节
- **THEN** 服务端通过 v3 HTTP 接口返回完整页图，不启动浏览器

#### Scenario: 需要登录的章节

- **WHEN** 章节内容仅登录可见且已配置登录态
- **THEN** 允许浏览器回退；未配置登录态时返回明确错误，不展示空阅读器

#### Scenario: 只允许站内 URL

- **WHEN** 解析请求携带非 copy4000.com 的 URL
- **THEN** Python 任务拒绝执行
