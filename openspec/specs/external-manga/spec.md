# external-manga Specification

## Purpose
TBD - created by archiving change implement-external-manga. Update Purpose after archive.

## Requirements

### Requirement: 外部漫画搜索与阅读

平台 MUST 支持通过 MangaDex 搜索外部漫画并在站内阅读托管章节。

#### Scenario: 搜索并阅读

- **WHEN** 用户在 /manga-ext 搜索并打开一部作品、选择可读章节
- **THEN** 阅读器按章节顺序竖向展示页面图片，支持切换省流/高清

#### Scenario: 外链章节

- **WHEN** 章节由 MangaDex 外链到官方站（无托管页图）
- **THEN** 列表不提供阅读入口，并显式提示切换语言或前往原站

### Requirement: 图片代理安全

图片代理 MUST 仅允许 MangaDex 官方图片域名，不得作为任意 URL 代理。

#### Scenario: 非白名单域名

- **WHEN** 请求 `u` 参数指向非 MangaDex 域名
- **THEN** 返回 400，不发起上游请求
