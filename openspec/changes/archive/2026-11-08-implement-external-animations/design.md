# 设计：M6 外部动画接入

## Context

docs/research/external-animation-integration.md 调研结论：方案 A（Kazumi 式
声明式规则）。本变更实现最小可用：规则导入 → 搜索代理 → 集绑定 → 展示。

## Goals / Non-Goals

**Goals**

- 管理员导入 Kazumi 兼容 JSON 规则（XPath + JSON API 两模式）
- 用户对外部源搜索动画 → 绑定为外部集 → 作品详情展示
- 播放地址识别：直链（.mp4/.m3u8）或跳转外部页面
- 规则源启停管理

**Non-Goals**

- WebView 嗅探 / 播放器解密 / 验证码处理（后置）
- 社区规则仓库自动拉取
- 多线路支持（单线路先做）
- 广告过滤 / HLS manifest 处理

## Decisions

### D1：规则格式兼容 Kazumi（API ≥ 3 子集）

支持字段：`api`, `type`, `name`, `baseURL`, `searchURL`, `searchList`,
`searchName`, `searchResult`, `chapterRoads`, `chapterResult`。
API 模式（`searchMode: "api"`）支持 JSONPath：`request{url,method}`,
`listPath`, `namePath`, `sourcePath`。其余字段（useWebview/useLegacyParser/
adBlocker 等）忽略不报错——前向兼容。

### D2：规则引擎在 Java 侧实现

XPath 用 `jsoup`（自带 XPath 支持），JSONPath 用 `com.jayway.jsonpath:json-path`。
不用 WebView——只解析 HTML/JSON 结构，不执行 JS。播放地址从 `chapterResult`
提取的链接中识别 `.mp4/.m3u8` 直链；否则视为"跳转页面"类型。

### D3：外部集绑定为用户操作（元数据与播放源分离）

搜索结果不自动绑定；所有者显式选择并绑定为外部集。外部集挂在作品下
（非动画版本下），与 MangaWeaver 产出的动画并列展示但不混排。

### D4：搜索结果缓存 5 分钟（防止频繁请求外部站点）

搜索结果缓存在内存（Caffeine 或 ConcurrentHashMap），TTL 5 分钟；
不落库（外部搜索结果不稳定，绑定才落库）。

## Migration Plan

V7 前向迁移；无结构变更到既有表。

## Open Questions

- XPath 库选型：jsoup 自带 `Selector` 有限，可能需要 `jsoup-xpath` 或
  `xsoup`——实现时按需选。
