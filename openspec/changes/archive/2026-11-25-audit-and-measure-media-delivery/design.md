# Design: 媒体交付链路审计、优化与端到端实测

## Context

依据 [docs/DESIGN.md](../../../docs/DESIGN.md) 第 13、14、18、19 节：
- 媒体字节由私有 S3 兼容对象存储（开发环境为 SeaweedFS 3.96）经短期预签名地址直出，Java API 只负责权限判定、元数据与签名签发，不将视频或原图加载进堆内存转发；
- 签名地址不落库、不进应用日志；权限按实时作品/版本状态判定；
- 前端不一次请求整本原图，视频播放须验证 `Range` / 拖动进度 / `Content-Type` / `Cache-Control`。

2026-11-25 对本地运行的 SeaweedFS 3.96 实测确认：
1. `PUT` 带 `Cache-Control` 后，`GET` 完整回传 `Cache-Control` 与 `ETag`；
2. `GET` 带 `If-None-Match` 返回 `304 Not Modified`（`0` 字节）；
3. `GET` 带 `Range: bytes=0-1023` 返回 `206 Partial Content`、`Accept-Ranges: bytes`、`Content-Range`；
4. 预签名 URL 带 `response-cache-control` 查询参数时，SeaweedFS 3.96 会按参数值覆盖响应 `Cache-Control` 头。

## Decisions

### D1. 分层 `Cache-Control` 策略与不可变产物元数据

不同媒体资产的变更频率与隐私边界不同，通过 `PresignService.MediaCacheProfile` 统一表达，不散落魔法字符串：

| 角色 | 场景 | `Cache-Control` 值 | 依据 |
| --- | --- | --- | --- |
| `IMMUTABLE_IMAGE` | 漫画阅读图 `reader.webp`、缩略图 `thumbnail.webp`、动画封面 `poster.jpg` | `private, max-age=86400, immutable` | 路径含不可变 `revisionId`/`pageId`（DESIGN 18.1），改图必建新版本；`private` 避免共享代理误缓存私有作品 |
| `IMMUTABLE_VIDEO` | 动画视频 `video.mp4` | `private, max-age=86400` | 支持浏览器分段缓存与 Range 续播，不破坏私有内容边界 |
| `IMMUTABLE_SUBTITLE` | 动画字幕 `subtitles.vtt` | `private, max-age=86400, immutable` | 与版本绑定不可变 |
| `DEFAULT` | 原上传文件或其他临时资产 | `private, max-age=300` | 保守短缓存 |

实现上双保险：
1. `ChapterParseHandler.uploadArtifact` 与 `AnimationVerifyHandler.uploadArtifact` 在 `PutObjectRequest` 写入对应 `cacheControl`；
2. `PresignService.presignGet(objectKey, profile)` 同时在 `GetObjectRequest` 设置 `.responseCacheControl(profile.headerValue())`，保证存量对象与用户直传的视频对象也能输出准确 `Cache-Control`。

### D2. 授权后短期签名窗口复用（保持实时鉴权边界）

问题：每次调用 `S3Presigner.presignGetObject` 都以 `Instant.now()` 签发，导致同一资产在 2 秒后签出的 URL QueryString 不同，浏览器磁盘缓存与 `ETag` 条件验证完全失效。

方案：
- 在 `PresignService` 中维护一个有界内存表（默认容量 2048，可经 `mangashelf.storage.presign-reuse-enabled` 开关，默认 `true`，便于基准对照关闭测试），按 `(objectKey, profile)` 缓存已签发的 `PresignedUrl`；
- 仅当缓存项剩余有效期 ≥ `max(60s, presignTtlSeconds / 2)`（默认 TTL=600s，即前 300s 复用、剩余 ≥ 300s）时返回同一 `PresignedUrl`，到期立即重新签发；
- **安全边界不放松**：所有调用方（`CatalogService.pages`、`MangaService`、`AnimationService.playback`、`AssetAccessService.authorizeRead`）均在实时权限/可见性/发布状态校验**之后**才调用 `presignGet`，一旦作品下架或改为私有，API 立即返回 404，不再返回该 URL；且签名本身最长仍在 `presignTtlSeconds`（10 分钟）内自然过期。

### D3. 消除 `pages()` 与 `playback()` 的 N+1 资产键查询

- 在 `AssetRepository` 增加 `Map<UUID, String> findObjectKeysByIds(Collection<UUID> ids)`，使用 PostgreSQL `WHERE id = ANY(?)` 一次查询批量返回；
- `CatalogService.pages` 先收集当前窗口内全部 `imageAssetId` + `thumbnailAssetId` 一次批量查出（20 页场景由 41 次 SQL 降为 2 次 SQL）；
- `AnimationService.playback` 一次查出视频、封面、字幕 3 个资产的 `object_key`（3 次 SQL 降为 1 次 SQL）。

### D4. Bangumi 封面代理支持强 `ETag` 与 `304 Not Modified`

- `ExternalController.bangumiCover` 对 `cover.bytes()` 计算 SHA-256 前 16 字节十六进制作为强 ETag（`"<32-hex>"`），通过请求头 `If-None-Match` 比对（或 `ResponseEntity.status(304)`），命中时返回 `304 Not Modified`、`ETag` 与 `Cache-Control: public, max-age=604800`，响应体为 0 字节，避免 Java API 重复传输封面二进制。

### D5. 本地漫画阅读器窗口复用与有限预加载

- `web/src/pages/Reader.tsx`：
  - 将 `windowPages` 升级为按 `ordinal` 索引的页面缓存映射（保留 `imageExpiresAt`）；
  - 翻页时若目标页已在本地映射中且距离过期 > 60 秒，**直接本地切换，不发 API 请求**；仅当当前页或 `current + 2` 缺失/临近过期时才按需拉取后续窗口（默认 `limit=10`）；
  - 有限预加载 `current + 1` 与 `current + 2` 两张阅读图（使用 `new Image()` 并去重已预加载 URL），绝不预加载整本原图（DESIGN 14.1）。

### D6. Nginx `/media/` 代理调优

- 在 `deploy/nginx/nginx.conf` 的 `location /media/` 增加：
  - `proxy_http_version 1.1;`
  - `proxy_set_header Connection "";`
  - `proxy_buffering off;`（避免 Nginx 先把大视频整段刷盘缓冲再下发，降低首帧 TTFB）
  - `proxy_force_ranges on;`（确保客户端字节 Range 始终透传可用）

### D7. 合规自生成媒体端到端实测方案

遵循规划文档第 1.3、7.2、14 节，严禁使用第三方未授权漫画或动画源作为性能验证依赖：
- 新增 `scripts/bench/run_media_bench.py`，现场用 Pillow 生成 20 张 1080×1600 合成漫画测试页（CBZ / WebP）与用 `ffmpeg` 生成 10 秒 1280×720 H.264+AAC `faststart` MP4 合规测试视频，写入真实 PostgreSQL 18 + SeaweedFS 3.96（通过标准登录 + 资产/作品/章节/动画入库，测完可选清理，不污染常规目录）；
- 同环境对照两个阶段：
  - **基线模式**（`--mangashelf.storage.presign-reuse-enabled=false` + 模拟旧 `Reader.tsx` 每翻一页重拉 API 无缓存、无 `If-None-Match`）；
  - **优化模式**（开启 `presign-reuse-enabled=true` + 批量 SQL + 分层 `Cache-Control` + 窗口复用 + `ETag`/`304` + `Range` 206 拖动）；
- 记录并报告：
  1. 漫画 20 页顺序翻页 + 回看 10 页场景：API 调用次数、SQL 查询次数、浏览器缓存/304 命中率、首图耗时、翻页 p95、客户端下载总字节、Java API 传输的媒体字节数（核实为 0 B）；
  2. 动画播放场景：`/playback` 延迟、对象存储首段 `Range: bytes=0-65535` TTFB（206）、中段拖动 `Range: bytes=262144-393215` 耗时（206）、响应头 `Accept-Ranges` / `Content-Range` / `Cache-Control` / `ETag`、Java API 传输的视频字节数（0 B）；
  3. `/api/v1/bangumi-covers/{subjectId}`：首次 200 vs 条件请求 `If-None-Match` 304 的字节数与延迟对比。

## Risks / Trade-offs

- **预签名 URL 复用与立即撤回窗口**：下架/改私有后，API 立即拒绝新签发（404），已签发给客户端的短期 URL 最长在其剩余 TTL（≤ 600s）内可直连对象存储——这与 S3 预签名机制本身的安全边界完全一致（DESIGN 18.1），未扩大暴露面；复用只是让前 300s 内的合法请求共享同一个已签 URL。
- **单实例内存表**：`PresignService` 的复用表按实例保存，多实例下若同一用户落在不同实例会得到两个不同的有效签名 URL，但仍完全正确；后续如多实例高并发需要跨副本一致 URL，可改为时间桶对齐时钟或共享存储，本期保持零额外中间件依赖。
