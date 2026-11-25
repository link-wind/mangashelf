# Change: 审计并优化媒体交付链路与端到端实测

## Why

代码审计发现 MangaShelf 的漫画页图、动画视频与封面交付链路存在五处可量化的性能与缓存短板：
1. 对象存储上传产物（`ChapterParseHandler`、`AnimationVerifyHandler`）与 `PresignService.presignGet` 均未写入或下发 `Cache-Control`，且每次 API 调用都生成含实时秒级 `X-Amz-Date` 的全新签名 URL，导致浏览器翻页回看缓存命中率为 0%；
2. `CatalogService.pages` 与 `AnimationService.playback` 逐资产循环查询 `assets.findObjectKey`，拉取 20 页章节清单触发 41 次 SQL（N+1）；
3. `/api/v1/bangumi-covers/{subjectId}` 仅有 `Cache-Control` 而缺少 `ETag` 与 `If-None-Match` 条件校验（`304 Not Modified`）；
4. 本地漫画阅读器 `Reader.tsx` 每次翻页都无条件重拉 API 窗口且缺少有限下一页图片预加载；
5. Nginx `/media/` 代理缺少 `proxy_http_version 1.1` 长连接与流式 Range 友好配置，且缺乏一套基于合规自生成媒体的端到端基线与优化对照实测。

## What Changes

- **对象级与签名级分层缓存头**：为不可变媒体产物（阅读图/缩略图/封面/字幕/视频）定义分层 `Cache-Control` 策略，上传时写入 S3 元数据，签发 `presignGet` 时同步通过 S3 `response-cache-control` 覆盖（兼容存量对象）；在 `PresignService` 增加授权后短期签名窗口复用（TTL 前半段复用同一 URL，剩余有效期 ≥ TTL/2，绝不跳过实时权限判定），使浏览器缓存与 `ETag`/`304` 生效。
- **消除媒体元数据 N+1 查询**：在 `AssetRepository` 增加批量 `findObjectKeysByIds`，改造 `CatalogService.pages`（41→2 SQL）与 `AnimationService.playback`（3→1 SQL）。
- **封面代理条件请求（ETag / 304）**：为 `/api/v1/bangumi-covers/{subjectId}` 增加基于内容摘要的强 `ETag` 与 `If-None-Match` 校验，命中时返回 `304 Not Modified`（0 字节响应体）。
- **本地漫画阅读器窗口复用与有限预加载**：改造 `web/src/pages/Reader.tsx`，按页序缓存已获取的未过期页面窗口，并在后台有限预加载 `current + 1` 与 `current + 2` 两页图片（不预加载整本）。
- **Nginx `/media/` 代理调优**：补充 `proxy_http_version 1.1`、`proxy_set_header Connection ""`、`proxy_buffering off` 与 `proxy_force_ranges on`，保障大视频 Range 拖动低首帧延迟。
- **合规自生成媒体实测脚本与验证报告**：新增 `scripts/bench/run_media_bench.py`，使用本机合规自生成 WebP 漫画与 H.264+AAC MP4 视频，实测优化前后 HTTP 头、304 命中率、Range 206 拖动、首图/首帧延迟、总下载字节与 Java API 媒体转发字节（确认 0 B 直连对象存储），沉淀报告到 `docs/validation/audit-and-measure-media-delivery.md` 并更新 `README.md`。

## Impact

- Affected specs: `manga-catalog`, `animation-viewing`
- Affected code:
  - `server/src/main/java/com/mangashelf/storage/{PresignService,AssetRepository,AssetAccessService}.java`
  - `server/src/main/java/com/mangashelf/catalog/{CatalogService,MangaService}.java`
  - `server/src/main/java/com/mangashelf/animation/{AnimationService,AnimationVerifyHandler}.java`
  - `server/src/main/java/com/mangashelf/ingestion/ChapterParseHandler.java`
  - `server/src/main/java/com/mangashelf/external/ExternalController.java`
  - `web/src/pages/Reader.tsx`
  - `deploy/nginx/nginx.conf`
  - `scripts/bench/run_media_bench.py`
  - `docs/validation/audit-and-measure-media-delivery.md`
  - `README.md`
