# 增量设计：7sefun 高清备用线路与 MacCMS 秒解快路径

> 设计依据：[docs/DESIGN.md](../../../docs/DESIGN.md) 第 12.2、16、25.1、25.3 节

## 1. 不新增表，复用 `external_episodes` 多源共存

`external_episodes` 已存在部分唯一索引 `(catalog_item_id, source_id, line_name, ordinal) WHERE catalog_item_id IS NOT NULL`（V14），天然支持同一目录条目挂载来自不同 `source_id` 的多条线路。

- **修复既有边界**：`ExternalCatalogService.ensureBrowseEpisodes` 原先以全部 `existing` 与主源 `parsed` 对比数量，多源共存时会导致每次浏览重复 upsert。本次改为仅按 `e.sourceId().equals(item.sourceId())` 比对主源剧集，保护附加线路不受覆盖。
- **线路标识与排序**：附加线路写入 `line_name = "7sefun_mp4"`、`line_rank = 10`（排在主源 `m3u8` 线路之后，不影响默认播放目标）。

## 2. 严格季期感知标题匹配（`RuleLineMatcher`）

复用 `TraditionalToSimplified` 与 `BangumiService.normalizeTitle` 提取基础片名，同时显式提取**季号**（如 `第2季 / 第二季 / Season 2 / S2 / Ⅱ`）与**类型标记**（`剧场版 / OVA / OAD / 总集篇`）：

- 若目录条目与候选结果都有明确季号且不一致，直接拒绝；
- 若一方明确为第 2 季及以上而另一方无季号（通常为第 1 季），直接拒绝；
- 剧场版 / OVA 类型不一致时拒绝；
- 基础片名归一化完全相等，或 bigram 相似度 ≥ 0.85 时命中最佳候选。

搜索关键词优先使用 `bangumi_subjects.name_cn`（若有）与 `external_catalog_items.name`，去重后依次查询，首个高置信命中即解析分集。

## 3. 安全与限流边界

- `POST /api/v1/catalog-items/{id}/extra-lines` 与 `POST /api/v1/external-episodes/*/resolve` 保持一致的公开只读/公共触发语义（放行访客触发，只读库内目录条目与管理员启用的规则源，绝不接收前端传入的任意 URL）。
- 对同一 `catalogItemId` 增加短时冷却/内存并发锁，避免重复冲击上游站；每用户/访客复用分钟级限流窗口。
- `ExternalService.statusOf` 检查 `resolved_url` 若含 `Expires=<unix_ts>`，须距离过期至少 120 秒才视为新鲜，否则返回空 `playUrl` 触发重新解析。

## 4. Python `player_aaaa` 纯 HTTP 快路径

在 `processing/tasks/external_resolve.py` 中，顺序执行：
1. `episode_url` 已是 `.m3u8/.mp4` → 直链快路径（原有）；
2. HTTP 获取剧集页 HTML（`IOError` 最多重试 2 次）→ 提取 `player_aaaa` JSON → 按 `encrypt`（0 原文 / 1 `unquote` / 2 `unquote(base64)`）解码 `url`；
3. 若解码结果为 `.m3u8/.mp4` → 发一次**不自动跟随重定向**的 `HEAD` 请求；若收到 `301/302/303/307/308` 且 `Location` 为合法 `http(s)` 视频地址，直接返回该 `Location`（避免大文件下载与多余 TLS 握手），否则返回解码后的直链；
4. 上述任一步未命中直链 → 回退至现有 Playwright 无头嗅探，失败显式返回 `NO_VIDEO_FOUND`。

## 5. 2026-11-28 补充：切集与切线性能优化决策

- **API 层同步快解（`FastPlaybackResolver`）**：对本身已是 `.m3u8/.mp4` 的直链（如量子资源 `lzm3u8`），`GET /resolve` 零任务、零队列、零 Python 进程直接返回；对标准 MacCMS 播放页（如 7sefun 七色B线），`POST /resolve` 在 API 层带并发合并锁同步 HTTP 秒解并落库，直接返回 `playUrl` 实现 0 次轮询。
- **分层缓存窗口**：带明确 `Expires` 参数的签名直链最长复用 60 分钟（距过期不足 120s 自动失效），无 `Expires` 的短效直链维持保守 4 分钟窗口。
- **Worker 防阻塞与前端体验**：`content.resolve` 消费并发度提至 2、Playwright 嗅探超时收紧至 18s、Outbox 扫描间隔调整为 200ms；前端 `useEpisodePlayback.ts` 改为渐进退避轮询（`250→350→500→800→1200→1500ms`）、隔离快速连切竞态，并在当前集起播 1.5s 后后台静默预热下一集。

