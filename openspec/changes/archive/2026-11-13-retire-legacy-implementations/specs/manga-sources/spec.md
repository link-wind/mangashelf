## MODIFIED Requirements

### Requirement: 外部漫画按源路由

外部漫画详情与阅读 MUST 支持按源分发；所有源（含 MangaDex）MUST 经统一的
manga-sources 接口提供详情、章节与页图，旧聚合端点在调用迁移后退役。
缺省源保持既有行为。

#### Scenario: 旧链接兼容

- **WHEN** 打开不带 source 参数的外部漫画详情/阅读链接
- **THEN** 按 MangaDex 处理（与历史进度兼容）

#### Scenario: MangaDex 走统一接口

- **WHEN** 前端打开 MangaDex 作品详情或章节页图
- **THEN** 与规则源使用同一 manga-sources 响应协议，不再调用旧聚合端点

## ADDED Requirements

### Requirement: 历史截图链接显式失效

哔哩历史截图阅读链接 MUST 返回明确的 410 提示，说明截图阅读已替换为 HTTP 页图，
MUST NOT 静默失败或恢复截图管线。

#### Scenario: 打开历史截图链接

- **WHEN** 用户访问历史哔哩截图会话或截图产物链接
- **THEN** 收到明确的 410 提示并引导使用 HTTP 页图阅读
