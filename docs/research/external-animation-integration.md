# 外部动画接入调研（DESIGN 2.1 后续需求）

> 日期：2026-11-08 · 调研范围：Kazumi/KazumiRules、Animeko、AnimeSpace、MoviePilot、AnimeOne、AniBaka

## 三条行业公理

1. **元数据与播放源分离**：条目数据来自权威源（Bangumi/你的 MangaWeaver），
   播放源只是"这一条目在第三方站的资源"，靠标题/ID 匹配挂接
   （Animeko KDoc 明文设计，Kazumi 也是）。
2. **每个资源必须带来源身份**：`sourceId`（全局唯一来源名）+
   `externalId/originalUrl`（回链），历史/收藏/偏好按此归属。
3. **规则必须可静态校验、带版本等级**：目标站点频繁改版，规则生命周期管理
   比规则表达力更重要。

## 横向对比

| 项目 | 抽象方式 | 规则载体 | 来源 ID | 播放解析 | 许可证 |
|---|---|---|---|---|---|
| Kazumi | 声明式规则（XPath/JSONPath） | 社区仓库 JSON | 中（历史存 pluginName+src） | WebView JS 钩子 | GPL-3.0 / 规则库 MIT |
| Animeko | `MediaSource` 接口 + 声明式源 | Kotlin 模块 | 强（mediaId 带前缀） | VideoSourceResolver | AGPL-3.0 |
| AniBaka | 管线 DSL（anx-rule/2，~30 op） | JSON 规则 | 中（标题匹配置信度） | 管线 op（含 WebView sniff） | GPL-3.0 |
| MoviePilot | 内置模块 + Python 插件市场 | 插件市场 repo | 强（tmdb/douban/bangumi 多 ID） | 无（交给媒体服务器） | GPL-3.0 |

## Kazumi 规则格式核心字段（AGE.json 示例）

```json
{
  "api": "5",
  "type": "anime",
  "name": "AGE",
  "multiSources": true,
  "useWebview": true, "useNativePlayer": true,
  "adBlocker": true,
  "baseURL": "https://www.agedm.io/",
  "searchURL": "https://www.agedm.io/search?query=@keyword",
  "searchList":  "//div[2]/div/section/div/div/div/div",
  "searchName":  "//div/div[2]/h5/a",
  "chapterRoads":  "//div[2]/div/section/div/div[2]/div[2]/div[2]/div",
  "chapterResult": "//ul/li/a"
}
```

搜索 → 详情 → 播放流程：
1. 元数据永远来自 Bangumi API（规则源不提供条目数据）
2. 对每个启用的 Plugin 用标题关键词并发搜索 → `SearchItem {name, src}`
3. 规则引擎（XPath 直接求值）按 `chapterRoads/chapterResult` 解析线路→分集
4. 分集 URL 交给 WebView 加载页面并注入 JS，钩住 `<video>` 抓取真实播放地址；
   抓到直链交给原生播放器（带 referer/UA/cookie），API≥5 支持 HLS 广告过滤

## 三种方案（按维护成本递增）

### 方案 A：Kazumi 式声明式规则源（推荐起步）

JSON 规则格式 + XPath/JSONPath 两模式。规则托管独立仓库（像 KazumiRules，MIT），
带 index.json 和 apiLevel 兼容矩阵。MangaShelf 数据模型加
`ExternalEpisode {sourceId, sourceName, externalUrl, episodePageUrl}` 挂在作品下。
播放页解析先只支持"直链 + m3u8"类站点，复杂的交给 WebView。

### 方案 B：AniBaka 式管线 DSL（方案 A 演进）

规则升级为 `search/detail/play` 三段 `PipelineStep [{op, params}]`
（~30 个 op：fetch/select/json/pick/regex/sniff/first + maccms 配方宏），
静态校验 + 可选 LLM 生成规则。表达力接近写代码但仍无任意代码执行。

### 方案 C：Animeko 式内置 AnimeSource 接口（少量第一方源）

`interface AnimeSource { sourceId; search(keyword); episodes(url); resolvePlayUrl(url) }`，
为 2–3 个有公开 API 的源写编译期实现，配合标题指纹匹配置信度引擎自动挂接。
声明式规则覆盖长尾站点，接口实现覆盖核心站点——Animeko 的混合路线。
