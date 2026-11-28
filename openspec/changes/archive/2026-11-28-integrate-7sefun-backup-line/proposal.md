# 变更提案：接入 7sefun 高清备用线路与 MacCMS 秒解快路径

> 关联设计：[docs/DESIGN.md](../../../docs/DESIGN.md) 第 12、17、25.3 节

## 为什么做

当前主站番剧目录与浏览态播放依赖单一 MacCMS 资源站（量子资源），在画质、线路容错和部分新番可用性上缺少备选。实测 `7sefun.top`（七色番）虽然关闭了 `/api.php/provide/vod/` 采集接口（无法整站同步目录），但其搜索页与详情页直出 HTML，且七色B线（`ndx`）可通过标准 MacCMS `player_aaaa (encrypt=2)` 解出高码率直连 MP4。

若仅作为手工规则源使用，存在两个体验瓶颈：
1. 只能手动绑定到用户私人作品，无法在主站番剧播放页直接切线；
2. 每次解析新集都要启动无头 Chromium（耗时 15–20 秒、瞬时 +300 MB 内存）。

## 范围与新增能力

1. **按需附加备用线路（零新表）**：新增 `POST /api/v1/catalog-items/{id}/extra-lines`，复用 `RuleEngine` 与严格季期感知标题匹配，将 `7sefun.top` 七色B线剧集以 `catalog_item_id` 幂等挂入现有 `external_episodes` 表，原生复用多线路切换、观看进度与访客浏览。
2. **MacCMS `player_aaaa` 纯 HTTP 秒解快路径**：在 `processing/tasks/external_resolve.py` 启动 Playwright 前，增加标准 `player_aaaa`（`encrypt=0/1/2`）解析与单跳 302 `Location` 提取，将单集解析耗时由 ~19 秒降至 ~1 秒并免去无头浏览器开销；不命中时平滑回退 Playwright。
3. **规则源网络重试与签名直链有效期保护**：为 `RuleEngine` 增加瞬时 `IOException` 短重试，为 `ExternalService` 增加带 `Expires` 参数直链的提前过期判定，并通过 Flyway `V38` 固化 `7sefun` 规则源配置。
4. **前端播放页轻量入口**：在 `BrowseWatch` 线路栏提供「+ 高清备用线」按需加载按钮，并将附加线路展示为友好的用户名称（不暴露底层站名或采集代号）。

## 明确不做

- 不整站爬取 `7sefun.top` 替代主目录源。
- 不针对七色A线（`lmm85` 嵌套反自动化 iframe）做反爬对抗。
- 不由 Java 服务端中转或托管第三方视频字节。
