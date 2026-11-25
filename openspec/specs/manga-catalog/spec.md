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

### Requirement: 阅读器章节目录

漫画阅读器（本地作品与外部漫画）MUST 提供章节目录面板：列出章节并高亮当前章，
点击跳转，支持正序/倒序切换；面板开关 MUST NOT 丢失当前阅读位置。

#### Scenario: 打开目录跳章

- **WHEN** 用户在阅读中打开目录并选择另一章
- **THEN** 阅读器加载所选章节并保持阅读模式设置，返回目录时当前章已更新

### Requirement: 阅读器键盘与滚轮导航

阅读器 MUST 支持键盘导航：单页/滚动（条漫）模式下 ↑/↓ 键逐页移动；双页模式下
←/→ 键翻页组，且鼠标滚轮 MUST 触发翻页。←/→ 的方向语义 MUST 遵循阅读方向设置
（RTL 时相反）；键位 MUST NOT 干扰已有 Esc 退出与 F 全屏。

#### Scenario: 条漫键盘逐页

- **WHEN** 用户在长条漫滚动模式下按 ↓ 或 ↑
- **THEN** 视口移动到下一页/上一页，与滚动位置保持一致

#### Scenario: 双页滚轮翻页

- **WHEN** 用户在双页模式滚动滚轮
- **THEN** 翻到下一组/上一组页面，连续滚动按节流处理不跳页

#### Scenario: RTL 方向键

- **WHEN** 阅读方向为 RTL 且用户按 →
- **THEN** 翻到上一组（与 LTR 相反）

### Requirement: 漫画索引页

平台 MUST 提供漫画索引页（参照番剧索引形态）：按数据源 tab 切换、标题搜索、
封面卡网格展示（标题、来源），并复用各源分区/榜单作为浏览入口；本地作品收藏
MUST NOT 与外部索引结果混淆展示。

#### Scenario: 按源搜索

- **WHEN** 用户选择某漫画源并搜索标题
- **THEN** 以封面网格展示该源搜索结果，可进入详情阅读

#### Scenario: 从索引进入阅读

- **WHEN** 用户点击任一结果卡
- **THEN** 进入该源作品详情，后续阅读与进度行为与既有链路一致

### Requirement: 目录列表精简投影与索引化读路径

番剧目录分页（`GET /api/v1/catalog-items`）与作品列表（`GET /api/v1/works`）MUST 避免向列表响应传输仅详情页使用的巨型播放线路载荷（`play_groups`），高频排序键（更新时间表达式、集数降序）、题材数组筛选（`bangumi_subjects.tags`）与公开作品游标查询 MUST 具备对应数据库索引支撑；目录详情与播放接口 MUST 仍完整返回线路信息，对外功能行为不变。

#### Scenario: 列表不传输巨型线路载荷而详情保持完整

- **WHEN** 客户端请求 `GET /api/v1/catalog-items` 获取番剧列表，随后请求其中一条的 `GET /api/v1/catalog-items/{id}`
- **THEN** 列表查询走索引且不读取 `play_groups` 巨型 JSONB，详情接口仍完整返回全部播放线路与集数信息

### Requirement: 热点只读缓存显式失效与可观测

针对压测证实的高频低变只读接口（本季日历、无筛选首屏目录与总计数），缓存层 MUST 具备明确 TTL、容量上限、并发回源合并（防缓存击穿）与显式失效路径（目录或 Bangumi 同步完成后主动失效），并通过 Micrometer 暴露缓存命中与未命中计数；缓存失效或禁用时 MUST 透明回源数据库。

#### Scenario: 同步完成后热点缓存立即失效

- **WHEN** 本季日历或番剧目录完成一次同步更新
- **THEN** 对应热点缓存被主动清空，下一次读取返回最新数据库结果，且命中/未命中指标可观测
