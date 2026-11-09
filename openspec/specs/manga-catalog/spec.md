# manga-catalog Specification

## Purpose
TBD - created by archiving change implement-manga-reading. Update Purpose after archive.

## Requirements

### Requirement: 作品与章节由所有者组织

登录用户 MUST 能创建作品并为其添加章节；章节在同一作品内 MUST 保持唯一顺序。
作品可见性（PRIVATE / PUBLIC）与对外状态（DRAFT / PUBLISHED / WITHDRAWN）MUST 分离存储，
章节继承作品可见性。列表与详情 MUST 只对 owner、管理员或满足公开条件（PUBLIC 且
PUBLISHED）的读者可见。

#### Scenario: 其他用户浏览私人草稿

- **WHEN** 用户 B 请求作品列表或用户 A 的 PRIVATE 作品详情
- **THEN** 该作品不出现在列表中，详情返回 404（按策略不暴露存在性）

### Requirement: 章节版本从已核验上传创建

系统 MUST 仅允许从状态为 VERIFIED 且属于当前用户的上传创建章节版本；
版本号在同一章节内 MUST 唯一递增。解析完成前版本处于 PROCESSING，页面清单完整后进入 READY，
解析失败进入 FAILED 且错误可见。

#### Scenario: 使用他人或未核验的上传创建版本

- **WHEN** 用户引用不属于自己的上传，或上传尚未通过核验
- **THEN** 系统拒绝创建章节版本（404 / 409）

#### Scenario: CBZ 解析产出固定页面清单

- **WHEN** chapter.parse 任务对合法 CBZ 执行完成
- **THEN** 版本进入 READY，页面按自然页序编号 1..N，逐页含阅读图与缩略图资产、
  宽高与摘要，page_count 与 manifest 摘要已固定

### Requirement: 页序确认在 READY 状态下进行

所有者 MUST 能在 READY 版本上确认或调整页序；请求 MUST 携带期望 edit_version，
不匹配时返回 409 及当前快照。已发布版本的页面清单 MUST NOT 被原地改写。

#### Scenario: 并发调序冲突

- **WHEN** 两个页序确认请求携带同一 edit_version 先后到达
- **THEN** 后到者收到 409 与最新快照，不发生静默覆盖

### Requirement: 发布原子切换当前版本

发布 MUST 在一个事务内完成：锁定章节行、校验版本仍为 READY 且属于该章节、
置版本 PUBLISHED、切换章节的 current_revision_id。当前版本指针 MUST 通过组合外键
保证属于本章节。两个并发发布请求 MUST 只产生一次成功的版本切换。

#### Scenario: 并发发布同一 READY 版本

- **WHEN** 两个发布请求同时提交同一 READY 版本
- **THEN** 恰好一个成功，另一者因状态不再 READY 而被拒绝，章节当前版本指向唯一已发布版本

#### Scenario: 换版后阅读不混页

- **WHEN** 章节发布新版本后，读者以旧版本的固定页面清单继续阅读
- **THEN** 返回的页面仍来自其请求的版本，不与新版本页面混排

### Requirement: 漫画专区索引

平台 MUST 提供独立的漫画索引，只列出有已发布章节的可见作品，并展示封面。

#### Scenario: 浏览漫画

- **WHEN** 用户打开 /manga
- **THEN** 展示封面卡（封面取首章首页缩略图）、话数与更新进度，支持标题搜索与分页

### Requirement: 漫画详情与续读

详情 MUST 展示封面、章节列表与逐章阅读位置，并提供直达续读位置的入口。

#### Scenario: 继续阅读

- **WHEN** 用户此前读过该作品
- **THEN** 详情页显示「继续阅读（第 X 话 · 第 N 页）」，点击直达该页

### Requirement: 沉浸式漫画阅读

阅读器 MUST 全屏沉浸显示，提供顶/底浮层与页码导航，并记住阅读位置。

#### Scenario: 继续阅读

- **WHEN** 用户打开上次读过的章节
- **THEN** 阅读器定位到上次页码，浮层可点击唤出，显示上一话/下一话与页码滑杆

### Requirement: 漫画详情章节格

详情页 MUST 以数字格展示章节，并支持语言与排序切换。

#### Scenario: 浏览章节

- **WHEN** 用户打开漫画详情
- **THEN** 看到大封面、信息与章节格；点击章节进入沉浸阅读
