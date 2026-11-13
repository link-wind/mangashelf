# external-manga Specification

## Purpose
TBD - created by archiving change implement-external-manga. Update Purpose after archive.

## Requirements

### Requirement: 外部漫画搜索与阅读

平台 MUST 通过统一数据源接口（manga-sources）按源搜索外部漫画并提供站内阅读；
MangaDex 与其他源 MUST 共用同一详情、章节与页图响应协议，前端 MUST NOT 按源分叉调用不同端点。

#### Scenario: 搜索并阅读

- **WHEN** 用户在 /manga-ext 搜索并打开一部作品、选择可读章节
- **THEN** 详情、章节与页图请求统一走按源接口，阅读器按章节顺序竖向展示页面图片，支持切换省流/高清

#### Scenario: 外链章节

- **WHEN** 章节由 MangaDex 外链到官方站（无托管页图）
- **THEN** 列表不提供阅读入口，并显式提示切换语言或前往原站

### Requirement: 图片代理安全

图片代理 MUST 仅允许 MangaDex 官方图片域名，不得作为任意 URL 代理。

#### Scenario: 非白名单域名

- **WHEN** 请求 `u` 参数指向非 MangaDex 域名
- **THEN** 返回 400，不发起上游请求

### Requirement: 阅读方向与页模式

阅读器 MUST 支持左到右/右到左（日漫）阅读方向，以及单页/双页/首页单排页模式，
设置持久化并影响点击分区、滑动方向与键盘翻页。

#### Scenario: 日漫方向翻页反转

- **WHEN** 阅读方向为 RTL 且用户点击右 30% 区域
- **THEN** 翻到上一组（与 LTR 相反）

### Requirement: 点击分区与滑动手势

翻页模式 MUST 支持点击左右 30% 区域翻页、中央 40% 切换浮层，以及横向滑动翻页。

#### Scenario: 滑动翻页

- **WHEN** 用户在翻页模式横向快速滑动超过阈值
- **THEN** 触发翻页；RTL 时方向反转

### Requirement: 长条漫自动滚动

阅读器 MUST 按页面图片比例自动识别长条漫，并默认使用滚动模式。

#### Scenario: 长条漫自动切滚动

- **WHEN** 章节前两张图的 高/宽 ≥ 3（竖向长条）
- **THEN** 阅读器切换为滚动模式并显示「长条漫 · 滚动」提示，即使全局设置为双页

#### Scenario: 普通页漫不受影响

- **WHEN** 页面图片接近常规比例（高/宽 < 3）
- **THEN** 阅读器保持用户选择的单页/双页模式

#### Scenario: 手动覆盖

- **WHEN** 用户在长条漫章节里手动选择双页
- **THEN** 本次章节按手动选择渲染

### Requirement: 外部漫画收藏与阅读记录分离

外部漫画 MUST 可按漫画源与作品标识收藏，收藏 MUST 独立于阅读进度：收藏不产生进度，
阅读不产生收藏。取消收藏 MUST NOT 删除该作品的阅读进度与历史记录。
收藏条目 MUST 保存可展示的标题快照，避免列表依赖上游接口实时查询。

#### Scenario: 收藏后再阅读

- **WHEN** 用户收藏一部外部漫画后再打开它阅读
- **THEN** 收藏列表仍只有一条该作品，阅读进度单独记录

#### Scenario: 取消收藏保留进度

- **WHEN** 用户取消收藏一部已阅读的外部漫画
- **THEN** 收藏列表移除该作品，历史与续读位置仍可继续使用
