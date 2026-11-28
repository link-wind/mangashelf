## ADDED Requirements

### Requirement: 番剧目录条目按需挂载外部规则源备用线路
系统 MUST 允许用户在番剧浏览播放页按需触发 `POST /api/v1/catalog-items/{id}/extra-lines`，从已启用的外部 HTML 规则源（如 7sefun）检索并校验同季匹配作品后，将可播剧集以独立线路幂等写入 `external_episodes`。

#### Scenario: 同季匹配成功挂载备用线路
- **WHEN** 用户对目录条目《葬送的芙莉莲 第二季》触发按需加载备用线路
- **THEN** 系统匹配到对应第二季条目（拒绝第一季错配），将七色B线剧集以独立 `line_name` 写入 `external_episodes` 并返回更新后的线路列表

#### Scenario: 主源追更不覆盖已挂载的备用线路
- **WHEN** 目录条目同时存在主资源站与外部规则源的剧集
- **THEN** `ensureBrowseEpisodes` 仅针对主资源站剧集做增量同步，不得删除或重复改写附加线路剧集

### Requirement: 外部剧集解析支持 MacCMS player_aaaa 纯 HTTP 快路径
`external.resolve` 任务 MUST 在启动无头浏览器前尝试通过 HTTP 解析标准 MacCMS `player_aaaa` 配置；当解码出 `.mp4/.m3u8` 直链时直接解析并返回结果，未命中时回退至 Playwright 嗅探。

#### Scenario: 七色B线剧集经纯 HTTP 快路径秒解
- **WHEN** Worker 执行 `external.resolve` 解析 `7sefun.top` 七色B线剧集页
- **THEN** 在不启动 Chromium 的情况下解码 `player_aaaa (encrypt=2)` 并获取最终 MP4 直链，返回 `SUCCEEDED` 与 `play_kind = mp4`

#### Scenario: 带 Expires 参数的签名直链临近过期自动失效
- **WHEN** `external_episodes.resolved_url` 携带 `Expires` 时间戳且距过期不足 120 秒
- **THEN** `GET /api/v1/external-episodes/{id}/resolve` MUST 不再返回过期 `playUrl`，引导客户端触发重新解析
